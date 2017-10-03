/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.jaeger.filters.jaxrs2;

import com.uber.jaeger.Constants;
import com.uber.jaeger.Span;
import com.uber.jaeger.SpanContext;
import com.uber.jaeger.Tracer;
import com.uber.jaeger.context.TracingUtils;
import com.uber.jaeger.propagation.FilterIntegrationTest;
import com.uber.jaeger.reporters.InMemoryReporter;
import com.uber.jaeger.samplers.ConstSampler;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that {@link ServerFilter} produces a span and sets tags correctly See also:
 * {@link FilterIntegrationTest} for a complete Client/Server filter integration test.
 */
public class ServerFilterTest extends JerseyTest {
  private InMemoryReporter reporter;
  private static Tracer tracer;

  @Override
  protected Application configure() {
    reporter = new InMemoryReporter();
    tracer = new Tracer.Builder("Angry Machine", reporter, new ConstSampler(true))
            .build();

    ResourceConfig resourceConfig = new ResourceConfig(HelloResource.class, EagleResource.class);
    ServerFilter filter = new ServerFilter(tracer, TracingUtils.getTraceContext());
    resourceConfig.register(filter);
    return resourceConfig;
  }

  @Path("heliosphan")
  public static class HelloResource {
    @GET
    public String getHello() {
      tracer.buildSpan("nested-span").startActive().close();
      return "Twinning";
    }
  }

  @Path("monsoon")
  public static class EagleResource {
    @GET
    public String getEagle() {
      return "Thorondor";
    }
  }

  @Test
  public void testOperationName() throws Exception {
    Response response = target("monsoon").request().get();
    Assert.assertEquals(200,response.getStatus());

    List<Span> spans = reporter.getSpans();
    Assert.assertFalse(spans.isEmpty());

    Assert.assertEquals("GET", spans.get(0).getOperationName());
  }

  @Test
  public void testPropagationThroughNestedSpan() throws Exception {
    Response response = target("heliosphan").request().get();
    Assert.assertEquals(200,response.getStatus());

    List<Span> spans = reporter.getSpans();

    Assert.assertEquals(2, spans.size());

    Assert.assertEquals(spans.get(0).context().getTraceId(),
                 spans.get(1).context().getTraceId());

    Assert.assertEquals(spans.get(0).context().getParentId(),
                 spans.get(1).context().getSpanId());
  }

  @Test
  public void testInject() throws Exception {
    HeaderTextMap headers = new HeaderTextMap();
    tracer.inject(SpanContext.contextFromString("4:3:2:1"), Format.Builtin.HTTP_HEADERS, headers);
    Response response = target("monsoon").request().headers(headers.getMap()).get();
    Assert.assertEquals(200,response.getStatus());

    List<Span> spans = reporter.getSpans();
    Assert.assertFalse(spans.isEmpty());

    Assert.assertEquals(4, spans.get(0).context().getTraceId());
  }

  @Test
  public void testUberHeader() throws Exception {
    Response response = target("monsoon").request().header(Constants.X_UBER_SOURCE, "origin").get();
    Assert.assertEquals(200,response.getStatus());

    List<Span> spans = reporter.getSpans();
    Assert.assertFalse(spans.isEmpty());

    Assert.assertEquals("origin", spans.get(0).getTags().get(Tags.PEER_SERVICE.getKey()));
  }

  private class HeaderTextMap implements TextMap {

    private MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      return null;
    }

    @Override
    public void put(String k, String v) {
      List<Object> valueList = new ArrayList<>();
      valueList.add(v);
      headers.put(k, valueList);
    }

    MultivaluedHashMap<String, Object> getMap() {
      return headers;
    }
  }

}
