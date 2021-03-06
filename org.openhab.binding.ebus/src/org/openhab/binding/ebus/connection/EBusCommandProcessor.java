/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.ebus.connection;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.binding.ebus.EBusBinding;
import org.openhab.binding.ebus.EBusBindingProvider;
import org.openhab.core.binding.BindingChangeListener;
import org.openhab.core.binding.BindingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Christian Sowada
 * @since 1.6.0
 */
public class EBusCommandProcessor implements BindingChangeListener {

	private static final Logger logger = LoggerFactory
			.getLogger(EBusCommandProcessor.class);

	private Map<String, ScheduledFuture<?>> futureMap = new HashMap<String, ScheduledFuture<?>>();
	private ScheduledExecutorService scheduler;
	private AbstractEBusConnector connector;

	private EBusBinding binding;

	/**
	 * @param connector
	 */
	public void setConnector(AbstractEBusConnector connector) {
		this.connector = connector;
	}

	/**
	 * 
	 */
	public void deactivate() {
		if(scheduler != null) {
			scheduler.shutdown();
		}
	}

	/* (non-Javadoc)
	 * @see org.openhab.core.binding.BindingChangeListener#allBindingsChanged(org.openhab.core.binding.BindingProvider)
	 */
	@Override
	public void allBindingsChanged(BindingProvider provider) {
		logger.debug("Remove all polling items for this provider from scheduler ...");
		for (String itemName : provider.getItemNames()) {
			if(futureMap.containsKey(itemName)) {
				futureMap.get(itemName).cancel(true);
			}
		}

		for (String itemName : provider.getItemNames()) {
			bindingChanged(provider, itemName);
		}
	}

	/* (non-Javadoc)
	 * @see org.openhab.core.binding.BindingChangeListener#bindingChanged(org.openhab.core.binding.BindingProvider, java.lang.String)
	 */
	@Override
	public void bindingChanged(BindingProvider provider, final String itemName) {

		logger.debug("Binding changed for item {}", itemName);

		final EBusBindingProvider eBusProvider = (EBusBindingProvider)provider;
		int refreshRate = eBusProvider.getRefreshRate(itemName);

		if(refreshRate > 0) {
			
			final Runnable r = new Runnable() {
				@Override
				public void run() {
					byte[] data = binding.getSendData(eBusProvider, itemName, null);
					if(data != null && data.length > 0) {
						connector.send(data);
					} else {
						logger.warn("No data to send for item {}! Check your item configuration.", itemName);
					}
				}
			};

			if(futureMap.containsKey(itemName)) {
				logger.debug("Stopped old polling item {} ...", itemName);
				futureMap.remove(itemName).cancel(true);
			}

			if(scheduler == null) {
				scheduler = Executors.newScheduledThreadPool(2);
			}

			logger.debug("Add polling item {} with refresh rate {} to scheduler ...",
					itemName, refreshRate);

			// do not start all pollings at the same time
			int randomInitDelay = (int) (Math.random() * (30 - 4) + 4);
			futureMap.put(itemName, scheduler.scheduleWithFixedDelay(r, randomInitDelay, 
					refreshRate, TimeUnit.SECONDS));

		} else if(futureMap.containsKey(itemName)) {
			logger.debug("Remove scheduled refresh for item {}", itemName);
			futureMap.get(itemName).cancel(true);
			futureMap.remove(itemName);
		}
	}

	/**
	 * @param binding
	 */
	public void setBinding(EBusBinding binding) {
		this.binding = binding;
	}
}
