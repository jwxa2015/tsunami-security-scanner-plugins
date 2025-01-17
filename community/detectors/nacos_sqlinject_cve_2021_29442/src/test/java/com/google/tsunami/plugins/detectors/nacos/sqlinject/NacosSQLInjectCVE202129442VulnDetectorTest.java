package com.google.tsunami.plugins.detectors.nacos.sqlinject;

import static com.google.common.truth.Truth.assertThat;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forHostname;
import static com.google.tsunami.common.data.NetworkEndpointUtils.forHostnameAndPort;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import com.google.tsunami.common.net.http.HttpClientModule;
import com.google.tsunami.common.net.http.HttpStatus;
import com.google.tsunami.common.time.testing.FakeUtcClock;
import com.google.tsunami.common.time.testing.FakeUtcClockModule;
import com.google.tsunami.proto.DetectionReportList;
import com.google.tsunami.proto.NetworkEndpoint;
import com.google.tsunami.proto.NetworkService;
import com.google.tsunami.proto.TargetInfo;
import com.google.tsunami.proto.TransportProtocol;
import java.io.IOException;
import java.time.Instant;
import javax.inject.Inject;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link NacosSQLInjectCVE202129442VulnDetector}.
 */
public final class NacosSQLInjectCVE202129442VulnDetectorTest {

  private final FakeUtcClock fakeUtcClock =
      FakeUtcClock.create().setNow(Instant.parse("2020-01-01T00:00:00.00Z"));

  @Inject
  private NacosSQLInjectCVE202129442VulnDetector detector;

  private MockWebServer mockWebServer;

  @Before
  public void setUp() {
    mockWebServer = new MockWebServer();
  }

  @After
  public void tearDown() throws IOException {
    mockWebServer.shutdown();
  }

  private void createInjector() {
    Guice.createInjector(
            new FakeUtcClockModule(fakeUtcClock),
            new HttpClientModule.Builder().build())
        .injectMembers(this);
  }

  @Test
  public void detect_whenVulnerable_returnsVulnerability()
      throws IOException {
    createInjector();
    mockWebServer.setDispatcher(new VulnerableEndpointDispatcher());
    mockWebServer.start();

    ImmutableList<NetworkService> httpServices = ImmutableList.of(
        NetworkService.newBuilder()
            .setNetworkEndpoint(
                forHostnameAndPort(mockWebServer.getHostName(), mockWebServer.getPort()))
            .setTransportProtocol(TransportProtocol.TCP)
            .setServiceName("http")
            .build());

    TargetInfo targetInfo = buildTargetInfo(forHostname(mockWebServer.getHostName()));
    DetectionReportList detectionReports = detector.detect(targetInfo, httpServices);
    assertThat(detectionReports.getDetectionReportsList()).containsExactly(
        detector.buildDetectionReport(targetInfo, httpServices.get(0)));
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
  }

  @Test
  public void detect_whenNoVulnerable_returnsNoFinding()
      throws IOException {
    createInjector();
    mockWebServer.setDispatcher(new SafeEndpointDispatcher());
    mockWebServer.start();

    ImmutableList<NetworkService> httpServices = ImmutableList.of(
        NetworkService.newBuilder()
            .setNetworkEndpoint(
                forHostnameAndPort(mockWebServer.getHostName(), mockWebServer.getPort()))
            .setTransportProtocol(TransportProtocol.TCP)
            .setServiceName("http")
            .build());

    TargetInfo targetInfo = buildTargetInfo(forHostname(mockWebServer.getHostName()));
    DetectionReportList detectionReports = detector.detect(targetInfo, httpServices);
    assertThat(detectionReports.getDetectionReportsList()).isEmpty();
    assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
  }

  private static final class VulnerableEndpointDispatcher extends Dispatcher {

    @Override
    public MockResponse dispatch(RecordedRequest recordedRequest) {
      if (recordedRequest.getPath().startsWith("/nacos/v1/cs/ops/derby")) {
        return new MockResponse().setResponseCode(HttpStatus.OK.code()).setBody(
            "{\"code\":200,\"message\":null,\"data\":[{\"USERNAME\":\"nacos\","
                + "\"PASSWORD\":\"$2a$10$EuWPZHzz32dJN7jexM34MOeYirDdFAZm2kuWj7VEOJhhZkDrxfvUu\","
                + "\"ENABLED\":true}]}");
      }
      return new MockResponse().setResponseCode(HttpStatus.OK.code());
    }
  }

  private static final class SafeEndpointDispatcher extends Dispatcher {

    @Override
    public MockResponse dispatch(RecordedRequest recordedRequest) {
      if (recordedRequest.getPath().startsWith("/nacos/v1/cs/ops/derby")) {
        return new MockResponse().setResponseCode(HttpStatus.FORBIDDEN.code());
      }
      return new MockResponse().setResponseCode(HttpStatus.OK.code());
    }
  }

  private static TargetInfo buildTargetInfo(NetworkEndpoint networkEndpoint) {
    return TargetInfo.newBuilder().addNetworkEndpoints(networkEndpoint).build();
  }
}
