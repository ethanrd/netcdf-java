package ucar.gcdm.server;

import com.google.common.base.Stopwatch;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import ucar.gcdm.GcdmGrpc.GcdmImplBase;
import ucar.gcdm.GcdmNetcdfProto;
import ucar.gcdm.GcdmNetcdfProto.DataRequest;
import ucar.gcdm.GcdmNetcdfProto.DataResponse;
import ucar.gcdm.GcdmNetcdfProto.Header;
import ucar.gcdm.GcdmNetcdfProto.HeaderRequest;
import ucar.gcdm.GcdmNetcdfProto.HeaderResponse;
import ucar.gcdm.GcdmConverter;
import ucar.ma2.Array;
import ucar.ma2.ArrayStructureW;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Section;
import ucar.ma2.StructureData;
import ucar.ma2.StructureDataIterator;
import ucar.ma2.StructureMembers;
import ucar.nc2.NetcdfFile;
import ucar.nc2.ParsedSectionSpec;
import ucar.nc2.Sequence;
import ucar.nc2.Variable;
import ucar.nc2.dataset.NetcdfDatasets;
import ucar.nc2.write.ChunkingIndex;

/** Server that manages startup/shutdown of a gCDM Server. */
public class GcdmServer {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GcdmServer.class);
  private static final int MAX_MESSAGE = 50 * 1000 * 1000; // 50 Mb LOOK could be tuned
  private static final int SEQUENCE_CHUNK = 1000;

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 16111;
    server = ServerBuilder.forPort(port) //
        .addService(new GcdmImpl()) //
        // .intercept(new MyServerInterceptor())
        .build().start();
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          GcdmServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });

    logger.info("Server started, listening on " + port);
    System.out.println("---> Server started, listening on " + port);
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /** Await termination on the main thread since the grpc library uses daemon threads. */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  /** Main launches the server from the command line. */
  public static void main(String[] args) throws IOException, InterruptedException {
    final GcdmServer server = new GcdmServer();
    server.start();
    server.blockUntilShutdown();
  }

  static class MyServerInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata requestHeaders,
        ServerCallHandler<ReqT, RespT> next) {
      System.out.printf("***ServerCall %s%n", call);
      System.out.printf("   Attributes %s%n", call.getAttributes());
      System.out.printf("   MethodDesc %s%n", call.getMethodDescriptor());
      System.out.printf("   Authority %s%n", call.getAuthority());
      System.out.printf("   Metadata %s%n", requestHeaders);
      return next.startCall(call, requestHeaders);
    }
  }

  static class GcdmImpl extends GcdmImplBase {

    @Override
    public void getNetcdfHeader(HeaderRequest req, StreamObserver<HeaderResponse> responseObserver) {
      System.out.printf("GcdmServer getHeader open %s%n", req.getLocation());
      HeaderResponse.Builder response = HeaderResponse.newBuilder();
      try (NetcdfFile ncfile = NetcdfDatasets.openFile(req.getLocation(), null)) {
        Header.Builder header = Header.newBuilder().setLocation(req.getLocation())
            .setRoot(GcdmConverter.encodeGroup(ncfile.getRootGroup(), 100).build());
        response.setHeader(header);
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
        logger.info("GcdmServer getHeader " + req.getLocation());
      } catch (Throwable t) {
        logger.warn("GcdmServer getHeader failed ", t);
        t.printStackTrace();
        response.setError(GcdmNetcdfProto.Error.newBuilder().setMessage(t.getMessage()).build());
      }
    }

    @Override
    public void getNetcdfData(DataRequest req, StreamObserver<DataResponse> responseObserver) {
      System.out.printf("GcdmServer getData %s %s%n", req.getLocation(), req.getVariableSpec());
      final Stopwatch stopwatch = Stopwatch.createStarted();
      long size = -1;

      try (NetcdfFile ncfile = NetcdfDatasets.openFile(req.getLocation(), null)) { // LOOK cache ncfile?
        ParsedSectionSpec varSection = ParsedSectionSpec.parseVariableSection(ncfile, req.getVariableSpec());
        Variable var = varSection.getVariable();
        if (var instanceof Sequence) {
          size = getSequenceData(ncfile, varSection, responseObserver);
        } else {
          Section wantSection = varSection.getArraySection();
          size = var.getElementSize() * wantSection.computeSize();
          getNetcdfData(ncfile, varSection, responseObserver);
        }
        responseObserver.onCompleted();
        logger.info("GcdmServer getData " + req.getLocation());

      } catch (Throwable t) {
        logger.warn("GcdmServer getData failed ", t);
        t.printStackTrace();
        DataResponse.Builder response =
            DataResponse.newBuilder().setLocation(req.getLocation()).setVariableSpec(req.getVariableSpec());
        response.setError(
            GcdmNetcdfProto.Error.newBuilder().setMessage(t.getMessage() == null ? "N/A" : t.getMessage()).build());
        responseObserver.onNext(response.build());
      }

      System.out.printf(" ** size=%d took=%s%n", size, stopwatch.stop());
    }

    private void getNetcdfData(NetcdfFile ncfile, ParsedSectionSpec varSection,
        StreamObserver<DataResponse> responseObserver) throws IOException, InvalidRangeException {
      Variable var = varSection.getVariable();
      Section wantSection = varSection.getArraySection();
      long size = var.getElementSize() * wantSection.computeSize();
      if (size > MAX_MESSAGE) {
        getDataInChunks(ncfile, varSection, responseObserver);
      } else {
        getOneChunk(ncfile, varSection, responseObserver);
      }
    }

    private void getDataInChunks(NetcdfFile ncfile, ParsedSectionSpec varSection,
        StreamObserver<DataResponse> responseObserver) throws IOException, InvalidRangeException {

      Variable var = varSection.getVariable();
      long maxChunkElems = MAX_MESSAGE / var.getElementSize();
      // LOOK wrong this assumes starts at 0, should start at varSection
      ChunkingIndex index = new ChunkingIndex(var.getShape());
      while (index.currentElement() < index.getSize()) {
        int[] chunkOrigin = index.getCurrentCounter();
        int[] chunkShape = index.computeChunkShape(maxChunkElems);
        Section section = new Section(chunkOrigin, chunkShape);
        ParsedSectionSpec spec = new ParsedSectionSpec(var, section);
        getOneChunk(ncfile, spec, responseObserver);
        index.setCurrentCounter(index.currentElement() + (int) Index.computeSize(chunkShape));
      }
    }

    private void getOneChunk(NetcdfFile ncfile, ParsedSectionSpec varSection,
        StreamObserver<DataResponse> responseObserver) throws IOException, InvalidRangeException {

      String spec = varSection.makeSectionSpecString();
      Variable var = varSection.getVariable();
      Section wantSection = varSection.getArraySection();

      DataResponse.Builder response = DataResponse.newBuilder().setLocation(ncfile.getLocation()).setVariableSpec(spec)
          .setVarFullName(var.getFullName()).setSection(GcdmConverter.encodeSection(wantSection));

      Array data = var.read(wantSection);
      response.setData(GcdmConverter.encodeData(data.getDataType(), data));

      responseObserver.onNext(response.build());
      System.out.printf(" Send one chunk %s size=%d bytes%n", spec,
          data.getSize() * varSection.getVariable().getElementSize());
    }


    private long getSequenceData(NetcdfFile ncfile, ParsedSectionSpec varSection,
        StreamObserver<DataResponse> responseObserver) throws InvalidRangeException, IOException {

      String spec = varSection.makeSectionSpecString();
      Sequence seq = (Sequence) varSection.getVariable();
      StructureMembers members = seq.makeStructureMembers();

      StructureData[] sdata = new StructureData[SEQUENCE_CHUNK];
      int start = 0;
      int count = 0;
      StructureDataIterator it = seq.getStructureIterator();
      while (it.hasNext()) {
        sdata[count++] = it.next();

        if (count >= SEQUENCE_CHUNK || !it.hasNext()) {
          StructureData[] correctSizeArray = Arrays.copyOf(sdata, count);
          ArrayStructureW sdataArray = new ArrayStructureW(members, new int[] {count}, correctSizeArray);
          Section section = Section.builder().appendRange(start, start + count).build();
          DataResponse.Builder response = DataResponse.newBuilder().setLocation(ncfile.getLocation())
              .setVariableSpec(spec).setVarFullName(seq.getFullName()).setSection(GcdmConverter.encodeSection(section));
          response.setData(GcdmConverter.encodeData(DataType.SEQUENCE, sdataArray));
          responseObserver.onNext(response.build());
          start = count;
          count = 0;
        }
      }
      return (long) (start + count) * members.getStructureSize();
    }
  } // GcdmImpl
}