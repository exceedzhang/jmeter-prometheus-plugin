package com.github.johrstrom.collector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;

public abstract class CollectorElement<C extends BaseCollectorConfig> extends AbstractTestElement {

	public static final String COLLECTOR_DEF = "prometheus.collector_definitions";
	
	protected Map<String,Collector> collectors = new HashMap<String,Collector>();
	
	private static Logger log = LoggerFactory.getLogger(CollectorElement.class);
	private static final long serialVersionUID = 963612021269632269L;
	
	public CollectorElement() {
		log.debug("making a new config element: " + this.toString());
		this.setCollectorConfigs(new ArrayList<C>());
	}
	
	public CollectionProperty getCollectorConfigs() {
		JMeterProperty collectorDefinitions = this.getProperty(COLLECTOR_DEF);
		
		if(collectorDefinitions == null || collectorDefinitions instanceof NullProperty) {
			collectorDefinitions = new CollectionProperty(COLLECTOR_DEF, new ArrayList<C>());
			collectorDefinitions.setName(COLLECTOR_DEF);
		}
		
		return (CollectionProperty) collectorDefinitions;
		
	}
	
	public void setCollectorConfigs(List<C> collectors) {
		log.debug("setting new collectors. size is: " + collectors.size());
		this.setCollectorConfigs(new CollectionProperty(COLLECTOR_DEF, collectors));
	}
	
	public void setCollectorConfigs(CollectionProperty collectors) {
		this.setProperty(collectors);
	}
	
	protected void registerAllCollectors() {
		for (Entry<String, Collector> entry : this.collectors.entrySet()) {
			entry.getValue().register(CollectorRegistry.defaultRegistry);
		}
	}
	
	protected void unRegisterAllCollectors() {
		for (Entry<String, Collector> entry : this.collectors.entrySet()) {
			CollectorRegistry.defaultRegistry.unregister(entry.getValue());
		}
	}
	
	protected void makeNewCollectors() {
		this.collectors.clear();
		
		CollectionProperty collectorDefs = this.getCollectorConfigs();
		PropertyIterator iter = collectorDefs.iterator();
		
		while(iter.hasNext()) {
			
			try {
				@SuppressWarnings("unchecked")
				C config = (C) iter.next().getObjectValue();
				Collector collector = BaseCollectorConfig.fromConfig(config);
				
				this.collectors.put(config.getMetricName(), collector);
				log.debug("added " + config.getMetricName() + " to list of collectors");
			}catch(Exception e) {
				log.error("Didn't create new collector because of error, ",e);
			}
			
		}
		
	}
	
	
}
