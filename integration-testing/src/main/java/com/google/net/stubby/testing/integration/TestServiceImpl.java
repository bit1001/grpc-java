package com.google.net.stubby.testing.integration;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.net.stubby.stub.StreamObserver;
import com.google.net.stubby.testing.integration.Messages.Payload;
import com.google.net.stubby.testing.integration.Messages.PayloadType;
import com.google.net.stubby.testing.integration.Messages.ResponseParameters;
import com.google.net.stubby.testing.integration.Messages.SimpleRequest;
import com.google.net.stubby.testing.integration.Messages.SimpleResponse;
import com.google.net.stubby.testing.integration.Messages.StreamingInputCallRequest;
import com.google.net.stubby.testing.integration.Messages.StreamingInputCallResponse;
import com.google.net.stubby.testing.integration.Messages.StreamingOutputCallRequest;
import com.google.net.stubby.testing.integration.Messages.StreamingOutputCallResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.EmptyProtos;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the business logic for the TestService. Uses an executor to schedule chunks
 * sent in response streams.
 */
public class TestServiceImpl implements TestServiceGrpc.TestService {
  private static final String COMPRESSABLE_FILE =
      "/com/google/net/stubby/testing/integration/testdata/compressable.txt";
  private static final String UNCOMPRESSABLE_FILE =
      "/com/google/net/stubby/testing/integration/testdata/uncompressable.bin";
  private final Random random = new Random();

  private final ScheduledExecutorService executor;
  private final ByteString uncompressableBuffer;
  private final ByteString compressableBuffer;

  /**
   * Constructs a controller using the given executor for scheduling response stream chunks.
   */
  public TestServiceImpl(ScheduledExecutorService executor) {
    this.executor = executor;
    this.compressableBuffer = createBufferFromFile(COMPRESSABLE_FILE);
    this.uncompressableBuffer = createBufferFromFile(UNCOMPRESSABLE_FILE);
  }

  @Override
  public void emptyCall(EmptyProtos.Empty empty,
                        StreamObserver<EmptyProtos.Empty> responseObserver) {
    responseObserver.onValue(EmptyProtos.Empty.getDefaultInstance());
    responseObserver.onCompleted();
  }

  /**
   * Immediately responds with a payload of the type and size specified in the request.
   */
  @Override
  public void unaryCall(SimpleRequest req,
        StreamObserver<SimpleResponse> responseObserver) {
    SimpleResponse.Builder responseBuilder = SimpleResponse.newBuilder();
    if (req.hasResponseType() && req.hasResponseSize()) {
      boolean compressable = compressableResponse(req.getResponseType());
      ByteString dataBuffer = compressable ? compressableBuffer : uncompressableBuffer;
      // For consistency with the c++ TestServiceImpl, use a random offset for unary calls.
      // TODO(user): whether or not this is a good approach needs further discussion.
      int offset = random.nextInt(
          compressable ? compressableBuffer.size() : uncompressableBuffer.size());
      ByteString payload = generatePayload(dataBuffer, offset, req.getResponseSize());
      responseBuilder.getPayloadBuilder()
          .setType(compressable ? PayloadType.COMPRESSABLE : PayloadType.UNCOMPRESSABLE)
          .setBody(payload);
    }
    responseObserver.onValue(responseBuilder.build());
    responseObserver.onCompleted();
  }

  /**
   * Given a request that specifies chunk size and interval between responses, creates and schedules
   * the response stream.
   */
  @Override
  public void streamingOutputCall(StreamingOutputCallRequest request,
      StreamObserver<StreamingOutputCallResponse> responseObserver) {
    // Create and start the response dispatcher.
    new ResponseDispatcher(responseObserver).enqueue(toChunkQueue(request)).completeInput();
  }

  /**
   * Waits until we have received all of the request messages and then returns the aggregate payload
   * size for all of the received requests.
   */
  @Override
  public StreamObserver<Messages.StreamingInputCallRequest> streamingInputCall(
      final StreamObserver<Messages.StreamingInputCallResponse> responseObserver) {
    return new StreamObserver<StreamingInputCallRequest>() {
      private int totalPayloadSize;

      @Override
      public void onValue(StreamingInputCallRequest message) {
        Payload payload = message.getPayload();
        if (payload.hasBody()) {
          totalPayloadSize += payload.getBody().size();
        }
      }

      @Override
      public void onCompleted() {
        responseObserver.onValue(StreamingInputCallResponse.newBuilder()
            .setAggregatedPayloadSize(totalPayloadSize).build());
        responseObserver.onCompleted();
      }

      @Override
      public void onError(Throwable cause) {
        responseObserver.onError(cause);
      }
    };
  }

  /**
   * True bi-directional streaming. Processes requests as they come in. Begins streaming results
   * immediately.
   */
  @Override
  public StreamObserver<Messages.StreamingOutputCallRequest> fullDuplexCall(
      final StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
    final ResponseDispatcher dispatcher = new ResponseDispatcher(responseObserver);
    return new StreamObserver<StreamingOutputCallRequest>() {
      @Override
      public void onValue(StreamingOutputCallRequest request) {
        dispatcher.enqueue(toChunkQueue(request));
      }

      @Override
      public void onCompleted() {
        // Tell the dispatcher that all input has been received.
        dispatcher.completeInput();
      }

      @Override
      public void onError(Throwable cause) {
        responseObserver.onError(cause);
      }
    };
  }

  /**
   * Similar to {@link #fullDuplexCall}, except that it waits for all streaming requests to be
   * received before starting the streaming responses.
   */
  @Override
  public StreamObserver<Messages.StreamingOutputCallRequest> halfDuplexCall(
      final StreamObserver<Messages.StreamingOutputCallResponse> responseObserver) {
    final Queue<Chunk> chunks = Lists.newLinkedList();
    return new StreamObserver<StreamingOutputCallRequest>() {
      @Override
      public void onValue(StreamingOutputCallRequest request) {
        chunks.addAll(toChunkQueue(request));
      }

      @Override
      public void onCompleted() {
        // Dispatch all of the chunks in one shot.
        new ResponseDispatcher(responseObserver).enqueue(chunks).completeInput();
      }

      @Override
      public void onError(Throwable cause) {
        responseObserver.onError(cause);
      }
    };
  }

  /**
   * Schedules the dispatch of a queue of chunks. Whenever chunks are added or input is completed,
   * the next response chunk is scheduled for delivery to the client. When no more chunks are
   * available, the stream is half-closed.
   */
  private class ResponseDispatcher {
    private final Queue<Chunk> chunks;
    private final StreamObserver<StreamingOutputCallResponse> responseStream;
    private volatile boolean isInputComplete;
    private boolean scheduled;
    private Runnable dispatchTask = new Runnable() {
      @Override
      public void run() {
        try {

          // Dispatch the current chunk to the client.
          try {
            dispatchChunk();
          } catch (RuntimeException e) {
            // Indicate that nothing is scheduled and re-throw.
            synchronized (ResponseDispatcher.this) {
              scheduled = false;
            }
            throw e;
          }

          // Schedule the next chunk if there is one.
          synchronized (ResponseDispatcher.this) {
            // Indicate that nothing is scheduled.
            scheduled = false;
            scheduleNextChunk();
          }
        } catch (Throwable t) {
          t.printStackTrace();
        }
      }
    };

    public ResponseDispatcher(StreamObserver<StreamingOutputCallResponse> responseStream) {
      this.chunks = Queues.newLinkedBlockingQueue();
      this.responseStream = responseStream;
    }

    /**
     * Adds the given chunks to the response stream and schedules the next chunk to be delivered if
     * needed.
     */
    public synchronized ResponseDispatcher enqueue(Queue<Chunk> moreChunks) {
      chunks.addAll(moreChunks);
      scheduleNextChunk();
      return this;
    }

    /**
     * Indicates that the input is completed and the currently enqueued response chunks are all that
     * remain to be scheduled for dispatch to the client.
     */
    public ResponseDispatcher completeInput() {
      isInputComplete = true;
      scheduleNextChunk();
      return this;
    }

    /**
     * Dispatches the current response chunk to the client. This is only called by the executor. At
     * any time, a given dispatch task should only be registered with the executor once.
     */
    private void dispatchChunk() {
      try {

        // Pop off the next chunk and send it to the client.
        Chunk chunk = chunks.remove();
        responseStream.onValue(chunk.toResponse());

      } catch (Throwable e) {
        responseStream.onError(e);
      }
    }

    /**
     * Schedules the next response chunk to be dispatched. If all input has been received and there
     * are no more chunks in the queue, the stream is closed.
     */
    private void scheduleNextChunk() {
      synchronized (this) {
        if (scheduled) {
          // Dispatch task is already scheduled.
          return;
        }

        // Schedule the next response chunk if there is one.
        Chunk nextChunk = chunks.peek();
        if (nextChunk != null) {
          scheduled = true;
          executor.schedule(dispatchTask, nextChunk.delayMicroseconds, TimeUnit.MICROSECONDS);
          return;
        }
      }

      if (isInputComplete) {
        // All of the response chunks have been enqueued but there is no chunks left. Close the
        // stream.
        responseStream.onCompleted();
      }

      // Otherwise, the input is not yet complete - wait for more chunks to arrive.
    }
  }

  /**
   * Breaks down the request and creates a queue of response chunks for the given request.
   */
  public Queue<Chunk> toChunkQueue(StreamingOutputCallRequest request) {
    Queue<Chunk> chunkQueue = new LinkedList<Chunk>();
    int offset = 0;
    boolean compressable = compressableResponse(request.getResponseType());
    for (ResponseParameters params : request.getResponseParametersList()) {
      int interval = params.hasIntervalUs() ? params.getIntervalUs() : 0;
      chunkQueue.add(new Chunk(interval, offset, params.getSize(), compressable));

      // Increment the offset past this chunk.
      // Both buffers need to be circular.
      offset = (offset + params.getSize()) %
          (compressable ? compressableBuffer.size() : uncompressableBuffer.size());
    }
    return chunkQueue;
  }

  /**
   * A single chunk of a response stream. Contains delivery information for the dispatcher and can
   * be converted to a streaming response proto. A chunk just references it's payload in the
   * {@link #uncompressableBuffer} array. The payload isn't actually created until {@link
   * #toResponse()} is called.
   */
  private class Chunk {
    private final int delayMicroseconds;
    private final int offset;
    private final int length;
    private final boolean compressable;

    public Chunk(int delayMicroseconds, int offset, int length, boolean compressable) {
      this.delayMicroseconds = delayMicroseconds;
      this.offset = offset;
      this.length = length;
      this.compressable = compressable;
    }

    /**
     * Convert this chunk into a streaming response proto.
     */
    private StreamingOutputCallResponse toResponse() {
      StreamingOutputCallResponse.Builder responseBuilder =
          StreamingOutputCallResponse.newBuilder();
      ByteString dataBuffer = compressable ? compressableBuffer : uncompressableBuffer;
      ByteString payload = generatePayload(dataBuffer, offset, length);
      responseBuilder.getPayloadBuilder()
          .setType(compressable ? PayloadType.COMPRESSABLE : PayloadType.UNCOMPRESSABLE)
          .setBody(payload);
      return responseBuilder.build();
    }
  }

  /**
   * Creates a buffer with data read from a file.
   */
  private ByteString createBufferFromFile(String fileClassPath) {
    ByteString buffer = ByteString.EMPTY;
    InputStream inputStream = getClass().getResourceAsStream(fileClassPath);
    if (inputStream == null) {
      throw new IllegalArgumentException("Unable to locate file on classpath: " + fileClassPath);
    }

    try {
      buffer = ByteString.readFrom(inputStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        inputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return buffer;
  }

  /**
   * Indicates whether or not the response for this type should be compressable. If {@code RANDOM},
   * picks a random boolean.
   */
  private boolean compressableResponse(PayloadType responseType) {
    switch (responseType) {
      case COMPRESSABLE:
        return true;
      case RANDOM:
        return random.nextBoolean();
      case UNCOMPRESSABLE:
      default:
        return false;
    }
  }

  /**
   * Generates a payload of desired type and size. Reads compressableBuffer or
   * uncompressableBuffer as a circular buffer.
   */
  private ByteString generatePayload(ByteString dataBuffer, int offset, int size) {
    ByteString payload = ByteString.EMPTY;
    // This offset would never pass the array boundary.
    int begin = offset;
    int end = 0;
    int bytesLeft = size;
    while (bytesLeft > 0) {
      end = Math.min(begin + bytesLeft, dataBuffer.size());
      // ByteString.substring returns the substring from begin, inclusive, to end, exclusive.
      payload = payload.concat(dataBuffer.substring(begin, end));
      bytesLeft -= (end - begin);
      begin = end % dataBuffer.size();
    }
    return payload;
  }
}