package com.github.johrstrom.listener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.apache.jmeter.assertions.AssertionResult;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.johrstrom.util.CollectorConfig;
import com.github.johrstrom.util.ServerInstantiator;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Summary;
import io.prometheus.client.exporter.MetricsServlet;

public class PrometheusBackendListener extends AbstractBackendListenerClient {

	public static final String SAVE_CONFIG = "johrstrom.save_config";

	private static final Logger log = LoggerFactory.getLogger(PrometheusBackendListener.class);

	// Samplers
	private Summary samplerCollector;
	private CollectorConfig samplerConfig = new CollectorConfig();
	private boolean collectSamples = true;

	// Thread counter
	private Gauge threadCollector;
	private boolean collectThreads = true;

	// Assertions
	private Collector assertionsCollector;
	private CollectorConfig assertionConfig = new CollectorConfig();
	private boolean collectAssertions = true;

	@Override
	public void handleSampleResults(List<SampleResult> arg0, BackendListenerContext arg1) {

		// build the label values from the event and observe the sampler
		// metrics
		for (SampleResult result : arg0) {

			try {
				String[] samplerLabelValues;

				samplerLabelValues = this.labelValues(result);

				if (collectSamples)
					samplerCollector.labels(samplerLabelValues).observe(result.getTime());

				if (collectThreads)
					if (JMeterContextService.getContext().getThreadGroup() != null)
						threadCollector.set(JMeterContextService.getContext().getThreadGroup().getNumberOfThreads());

				// if there are any assertions to
				if (collectAssertions && result.getAssertionResults().length > 0) {
					for (AssertionResult assertionResult : result.getAssertionResults()) {
						String[] assertionsLabelValues = this.labelValues(result, assertionResult);

						if (assertionsCollector instanceof Summary)
							((Summary) assertionsCollector).labels(assertionsLabelValues).observe(result.getTime());
						else if (assertionsCollector instanceof Counter)
							((Counter) assertionsCollector).labels(assertionsLabelValues).inc();
					}
				}
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

	}

	@Override
	public void setupTest(BackendListenerContext context) throws Exception {

		super.setupTest(context);
		this.reconfigure(context);
		int port = context.getIntParameter("port", 9270);
		Server server = ServerInstantiator.getInstance(port);

		ServletContextHandler servlet_context = new ServletContextHandler();
		servlet_context.setContextPath("/");
		server.setHandler(servlet_context);
		servlet_context.addServlet(new ServletHolder(new MetricsServlet()), "/metrics");

		try {
			server.start();
		} catch (Exception e) {
			log.error("Couldn't start http server", e);
		}

	}

	@Override
	public void teardownTest(BackendListenerContext context) throws Exception {
		try {
			ServerInstantiator.getInstance(context.getIntParameter("port", 9270)).stop();
		} catch (Exception e) {
			log.error("Couldn't stop http server", e);
		}
		super.teardownTest(context);
	}

	/**
	 * Helper function to modify private member collectors and collector
	 * configurations. Any invocation of this method will modify them, even if
	 * configuration fails due to reflection errors, default configurations are
	 * applied and new collectors created.
	 */
	protected void reconfigure(BackendListenerContext context) {

		CollectorConfig tmpAssertConfig = new CollectorConfig();
		CollectorConfig tmpSamplerConfig = new CollectorConfig();

		// activate collections
		collectSamples = context.getBooleanParameter("Samples", true) || context.getBooleanParameter("Code", true)
				|| context.getBooleanParameter("Labels", true);
		collectThreads = context.getBooleanParameter("Threads", true);
		collectAssertions = context.getBooleanParameter("Assertions", true);

		try {
			// try to build new config objects
			tmpAssertConfig = this.newAssertionCollectorConfig(context);
			tmpSamplerConfig = this.newSamplerCollectorConfig(context);

		} catch (NoSuchMethodException | SecurityException e) {
			log.error("Only partial reconfigure due to exception.", e);
		}

		// remove old collectors and reassign member variables
		CollectorRegistry.defaultRegistry.clear();
		this.assertionConfig = tmpAssertConfig;
		this.samplerConfig = tmpSamplerConfig;

		// register new collectors
		if (collectSamples)
			this.samplerCollector = Summary.build().name("jmeter_samples_latency").help("Summary for Sample Latency")
					.labelNames(this.samplerConfig.getLabels()).quantile(0.5, 0.1).quantile(0.99, 0.1).create()
					.register(CollectorRegistry.defaultRegistry);

		if (collectThreads)
			this.threadCollector = Gauge.build().name("jmeter_running_threads").help("Counter for running threds")
					.create().register(CollectorRegistry.defaultRegistry);

		if (collectAssertions)
			this.createAssertionCollector(context.getBooleanParameter("Assertions_Summary_Results", true));

		log.info("Reconfigure complete.");

		if (log.isDebugEnabled()) {
			log.debug("Assertion Configuration: " + this.assertionConfig.toString());
			log.debug("Sampler Configuration: " + this.samplerConfig.toString());
		}

	}

	/**
	 * Create a new CollectorConfig for Assertions. Due to reflection this
	 * throws errors based on security and absence of method definitions.
	 * 
	 * @return
	 * @throws NoSuchMethodException
	 * @throws SecurityException
	 */
	protected CollectorConfig newAssertionCollectorConfig(BackendListenerContext context)
			throws NoSuchMethodException, SecurityException {
		CollectorConfig collectorConfig = new CollectorConfig();

		if (context.getBooleanParameter("Assertions", true)) {
			// TODO configure assertions more granularly
			collectorConfig.saveSamplerLabel();
			collectorConfig.saveAssertionFailure();
			collectorConfig.saveAssertionName();
		}

		return collectorConfig;
	}

	/**
	 * Create a new CollectorConfig for Samplers. Due to reflection this throws
	 * errors based on security and absence of method definitions.
	 * 
	 * @return the new CollectorConfig
	 * @throws SecurityException
	 * @throws NoSuchMethodException
	 */
	protected CollectorConfig newSamplerCollectorConfig(BackendListenerContext context)
			throws NoSuchMethodException, SecurityException {
		CollectorConfig collectorConfig = new CollectorConfig();

		if (context.getBooleanParameter("Label", true)) {
			collectorConfig.saveSamplerLabel();
		}

		if (context.getBooleanParameter("Code", true)) {
			collectorConfig.saveSamlerCode();
		}

		if (context.getBooleanParameter("Success", true)) {
			collectorConfig.saveSamplerSuccess();
		}

		return collectorConfig;
	}

	protected void createAssertionCollector(boolean summary) {
		if (summary)
			this.assertionsCollector = Summary.build().name("jmeter_assertions_total").help("Counter for assertions")
					.labelNames(this.assertionConfig.getLabels()).quantile(0.5, 0.1).quantile(0.99, 0.1).create()
					.register(CollectorRegistry.defaultRegistry);

		else
			this.assertionsCollector = Counter.build().name("jmeter_assertions_total").help("Counter for assertions")
					.labelNames(this.assertionConfig.getLabels()).create().register(CollectorRegistry.defaultRegistry);

	}

	/**
	 * For a given SampleEvent, get all the label values as determined by the
	 * configuration. Can return reflection related errors because this invokes
	 * SampleEvent accessor methods like getResponseCode or getSuccess.
	 * 
	 * @param event
	 *            - the event that occurred
	 * @return
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 */
	protected String[] labelValues(SampleResult result)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {

		String[] values = new String[this.samplerConfig.getLabels().length];

		for (int i = 0; i < values.length; i++) {
			Method m = this.samplerConfig.getMethods()[i];
			values[i] = m.invoke(result).toString();
		}

		return values;

	}

	/**
	 * For a given SampleEvent and AssertionResult, get all the label values as
	 * determined by the configuration. Can return reflection related errors
	 * because this invokes SampleEvent accessor methods like getResponseCode or
	 * getSuccess.
	 * 
	 * @param event
	 *            - the event that occurred
	 * @param assertionResult
	 *            - the assertion results associated to the event
	 * @return
	 * @throws IllegalAccessException
	 * @throws IllegalArgumentException
	 * @throws InvocationTargetException
	 */
	protected String[] labelValues(SampleResult result, AssertionResult assertionResult)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {

		String[] values = new String[this.assertionConfig.getLabels().length];

		for (int i = 0; i < values.length; i++) {
			Method m = this.assertionConfig.getMethods()[i];
			if (m.getDeclaringClass().equals(AssertionResult.class))
				values[i] = m.invoke(assertionResult).toString();
			else
				values[i] = m.invoke(result).toString();
		}

		return values;

	}

	@Override
	public Arguments getDefaultParameters() {
		Arguments arguments = new Arguments();
		arguments.addArgument("port", "9270");
		arguments.addArgument("Code", "true");
		arguments.addArgument("Success", "true");
		arguments.addArgument("Label", "true");
		arguments.addArgument("Assertions", "true");
		arguments.addArgument("Assertions_Summary_Results", "true");
		return arguments;
	}

}
