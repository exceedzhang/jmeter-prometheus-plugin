package com.github.johrstrom.listener;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.prometheus.client.hotspot.DefaultExports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import org.apache.jmeter.util.JMeterUtils;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

/**
 * Expose Prometheus metrics using a plain Java HttpServer.
 * <p>
 * Example Usage:
 * <pre>
 * {@code
 * HTTPServer server = new HTTPServer(1234);
 * }
 * </pre>
 * */
public class PrometheusServer {
	
	public static final String PROMETHEUS_PORT = "prometheus.port";
	public static final int PROMETHEUS_PORT_DEFAULT = 9270;
	
	public static final String PROMETHEUS_DELAY = "prometheus.delay";
	public static final int PROMETHEUS_DELAY_DEFAULT = 0;

	private static class LocalByteArray extends ThreadLocal<ByteArrayOutputStream> {
	    protected ByteArrayOutputStream initialValue() {
	        return new ByteArrayOutputStream(1 << 20);
	    }
	}

    static class HTTPMetricHandler implements HttpHandler {
        private CollectorRegistry registry;
        private final LocalByteArray response = new LocalByteArray();

        HTTPMetricHandler(CollectorRegistry registry) {
          this.registry = registry;
        }


        public void handle(HttpExchange t) throws IOException {
            String query = t.getRequestURI().getRawQuery();

            ByteArrayOutputStream response = this.response.get();
            response.reset();
            OutputStreamWriter osw = new OutputStreamWriter(response);
            TextFormat.write004(osw,
                    registry.filteredMetricFamilySamples(parseQuery(query)));
            osw.flush();
            osw.close();
            response.flush();
            response.close();

            t.getResponseHeaders().set("Content-Type",
                    TextFormat.CONTENT_TYPE_004);
            if (shouldUseCompression(t)) {
                t.getResponseHeaders().set("Content-Encoding", "gzip");
                t.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
                final GZIPOutputStream os = new GZIPOutputStream(t.getResponseBody());
                response.writeTo(os);
                os.close();
            } else {
                t.getResponseHeaders().set("Content-Length",
                        String.valueOf(response.size()));
                t.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.size());
                response.writeTo(t.getResponseBody());
            }
            t.close();
        }

    }

    protected static boolean shouldUseCompression(HttpExchange exchange) {
        List<String> encodingHeaders = exchange.getRequestHeaders().get("Accept-Encoding");
        if (encodingHeaders == null) return false;

        for (String encodingHeader : encodingHeaders) {
            String[] encodings = encodingHeader.split(",");
            for (String encoding : encodings) {
                if (encoding.trim().toLowerCase().equals("gzip")) {
                    return true;
                }
            }
        }
        return false;
    }

    protected static Set<String> parseQuery(String query) throws IOException {
        Set<String> names = new HashSet<String>();
        if (query != null) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx != -1 && URLDecoder.decode(pair.substring(0, idx), "UTF-8").equals("name[]")) {
                    names.add(URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                }
            }
        }
        return names;
    }

    private HttpServer server;
    private static PrometheusServer instance = null;
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private int port = JMeterUtils.getPropDefault(PROMETHEUS_PORT, PROMETHEUS_PORT_DEFAULT);
    private int delay = JMeterUtils.getPropDefault(PROMETHEUS_DELAY, PROMETHEUS_DELAY_DEFAULT);
    
    protected static final HTTPMetricHandler metricHandler = new HTTPMetricHandler(CollectorRegistry.defaultRegistry);

    public synchronized static PrometheusServer getInstance() {
    	if(instance == null) {
    		instance = new PrometheusServer();
    	}
    	
    	return instance;
    }

    /**
     * Start a HTTP server serving Prometheus metrics from the given registry.
     */
    private PrometheusServer() {
    	DefaultExports.initialize();
    }
    
    public void start() throws IOException {
    	if(server != null){
    		server.stop(0);
    	}
    	
        server = HttpServer.create();
        InetSocketAddress addr = new InetSocketAddress(port);
        
        server.bind(addr, 3);
        
        server.createContext("/", metricHandler);
        server.createContext("/metrics", metricHandler);
        

        server.setExecutor(executorService);
        server.start();      
    }
    
    public void stop() {
    	server.stop(delay);
    }


}

