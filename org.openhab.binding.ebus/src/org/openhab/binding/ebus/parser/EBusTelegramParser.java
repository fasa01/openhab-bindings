/**
 * Copyright (c) 2010-2014, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.ebus.parser;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptException;

import org.openhab.binding.ebus.EBusTelegram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Christian Sowada
 * @since 1.6.0
 */
public class EBusTelegramParser {

	private static final Logger logger = LoggerFactory
			.getLogger(EBusTelegramParser.class);

	private static final Logger logger2 = LoggerFactory
			.getLogger(EBusTelegramParser.class.getPackage().getName() + ".Analyses");

	private static final Logger logger3 = LoggerFactory
			.getLogger(EBusTelegramParser.class.getPackage().getName() + ".BruteForce");

	private Map<String, Object> settings;

	private EBusConfigurationProvider configurationProvider; 

	public EBusTelegramParser(EBusConfigurationProvider configurationProvider) {
		this.configurationProvider = configurationProvider;
	}

	/**
	 * @param byteBuffer
	 * @param type
	 * @param pos
	 * @return
	 */
	private Object getValue(ByteBuffer byteBuffer, String type, int pos, 
			BigDecimal min, BigDecimal max, BigDecimal replaceValue, BigDecimal factor) {

		Object value = null;
		//		BigDecimal b = null;
		byte hByte = 0;
		byte lByte = 0;

		BigDecimal repVal = null;

		if(pos > byteBuffer.position()) {
			logger.warn("Wow, buffer pos error!");
		}

		switch (type) {
		case "data2b":
			hByte = byteBuffer.get(pos);
			lByte = byteBuffer.get(pos-1);
			//			value = EBusUtils.decodeDATA2b(hByte, lByte);
			repVal = BigDecimal.valueOf(-128);
			//			if((float)value == -128) value = null;
			value = new BigDecimal(EBusUtils.decodeDATA2b(hByte, lByte));
			break;

		case "data2c":
			hByte = byteBuffer.get(pos);
			lByte = byteBuffer.get(pos-1);
			repVal = BigDecimal.valueOf(-2048);
			value = new BigDecimal(EBusUtils.decodeDATA2c(hByte, lByte));
			break;

		case "data1c":
			lByte = byteBuffer.get(pos-1);
			repVal = BigDecimal.valueOf(255);
			value = new BigDecimal(EBusUtils.decodeDATA1c(lByte));
			break;

		case "data1b":
			lByte = byteBuffer.get(pos-1);
			repVal = BigDecimal.valueOf(-128);
			value = new BigDecimal(EBusUtils.decodeDATA1b(lByte));
			break;

		case "bcd":
			lByte = byteBuffer.get(pos-1);
			repVal = BigDecimal.valueOf(266);
			value = new BigDecimal(EBusUtils.decodeBCD(lByte));
			break;

		case "word":
			hByte = byteBuffer.get(pos);
			lByte = byteBuffer.get(pos-1);
			repVal = BigDecimal.valueOf(65535);
			value = new BigDecimal(EBusUtils.decodeWORD(hByte, lByte));
			break;

		case "uchar":
		case "byte":
			repVal = BigDecimal.valueOf(255);
			value = new BigDecimal(byteBuffer.get(pos-1) & 0xFF);
			break;

		case "char":
			repVal = BigDecimal.valueOf(255);
			value = new BigDecimal(byteBuffer.get(pos-1));
			break;

		case "bit":
			int bit = ((Long) settings.get("bit")).intValue();
			value = byteBuffer.get(pos-1);

			boolean isSet = ((byte)value >> bit& 0x1) == 1;
			value = isSet;
			break;

		default:
			logger.warn("Configuration Error: Unknown command type! {}", type);
			break;
		}

		if(replaceValue != null) {
			repVal = replaceValue;
		}

		if(value instanceof BigDecimal) {
			BigDecimal b = (BigDecimal)value;

			if(repVal != null && b.compareTo(repVal) == 0) {
				logger.trace("Replace value found, skip value ...");
				value = b = null;
			}
			
			// multiply before check min and max
			if(b != null && factor != null) {
				logger.trace("Value multiplied ...");
				value = b = b.multiply(factor);
			}
			
			if(min != null && b != null && b.compareTo(min) == -1) {
				logger.trace("Minimal value reached, skip value ...");
				value = b = null;
				
			} else if (max != null && b != null && b.compareTo(max) == 1) {
				logger.trace("Maximal value reached, skip value ...");
				value = b = null;
			}

		}

		return value;
	}

	/**
	 * @param entry
	 * @param bindings2
	 * @return
	 * @throws ScriptException
	 */
	private Object evaluateScript(Entry<String, Map<String, Object>> entry, Map<String, Object> bindings2) throws ScriptException {
		Object value = null;
		if(entry.getValue().containsKey("cscript")) {
			CompiledScript cscript = (CompiledScript) entry.getValue().get("cscript");

			// Add global variables thisValue and keyName to JavaScript context
			Bindings bindings = cscript.getEngine().createBindings();
			bindings.putAll(bindings2);
			value = cscript.eval(bindings);
		}

		if(value instanceof Long) {
			value = new BigDecimal((long)value);
		} else if(value instanceof Integer) {
			value = new BigDecimal((int)value);
		} else if(value instanceof Float) {
			value = new BigDecimal((float)value);
		} else if(value instanceof Double) {
			value = new BigDecimal((double)value);
		}

		return value;
	}

	/**
	 * @param telegram
	 */
	private void bruteforceEBusTelegram(EBusTelegram telegram) {

		byte[] data = telegram.getData();

		String format = String.format("%-4s%-13s%-13s%-13s%-13s%-13s%-13s", "Pos", "WORD", "UInt", "DATA2B", "DATA2C", "DATA1c", "BCD");
		logger3.trace("    " + format);
		logger3.trace("    -----------------------------------------------------------------------------");

		for (int i = 0; i < data.length; i++) {

			Object word = i == data.length-1 ? "---" : EBusUtils.decodeWORD(data[i+1], data[i]);
			Object data2b = i == data.length-1 ? "---" : EBusUtils.decodeDATA2b(data[i+1], data[i]);
			Object data2c = i == data.length-1 ? "---" : EBusUtils.decodeDATA2c(data[i+1], data[i]);
			Object data1c = i == data.length-1 ? "---" : EBusUtils.decodeDATA1c(data[i+1]);
			int bcd = EBusUtils.decodeBCD(data[i]);
			//			int uint = EBusUtils.unsignedInt(data[i]);
			int uint = data[i] & 0xFF;
			format = String.format("%-4s%-13s%-13s%-13s%-13s%-13s%-13s", i+6, word, uint, data2b, data2c, data1c, bcd);
			logger3.trace("    " + format);
		}

		if(telegram.getType() == EBusTelegram.MASTER_SLAVE) {
			data = telegram.getSlaveData();

			logger3.trace("    ---------------------------------- Answer ----------------------------------");

			for (int i = 0; i < data.length; i++) {

				Object word = i == data.length-1 ? "---" : EBusUtils.decodeWORD(data[i+1], data[i]);
				Object data2b = i == data.length-1 ? "---" : EBusUtils.decodeDATA2b(data[i+1], data[i]);
				Object data2c = i == data.length-1 ? "---" : EBusUtils.decodeDATA2c(data[i+1], data[i]);
				Object data1c = i == data.length-1 ? "---" : EBusUtils.decodeDATA1c(data[i+1]);
				int bcd = EBusUtils.decodeBCD(data[i]);
				//				int uint = EBusUtils.unsignedInt(data[i]);
				int uint = data[i] & 0xFF;

				format = String.format("%-4s%-13s%-13s%-13s%-13s%-13s%-13s", i+6, word, uint, data2b, data2c, data1c, bcd);
				logger3.trace("    " + format);
			}

		}
	}

	/**
	 * @param telegram
	 * @return
	 */
	public Map<String, Object> parse(EBusTelegram telegram) {

		if(configurationProvider == null) {
			logger.error("Configuration not loaded, can't parse telegram!");
			return null;
		}

		final Map<String, Object> valueRegistry = new HashMap<String, Object>();
		final Map<String, Object> valueRegistry2 = new HashMap<String, Object>();

		if(telegram == null) {
			return null;
		}

		final ByteBuffer byteBuffer = telegram.getBuffer();
		final String bufferString = EBusUtils.toHexDumpString(byteBuffer).toString();

		final List<Map<String, Object>> matchedTelegramRegistry = configurationProvider.getCommandsByFilter(bufferString);

		logger2.debug(bufferString);

		if(matchedTelegramRegistry.isEmpty()) {
			logger2.debug("  >>> Unknown ----------------------------------------");
			if(logger3.isTraceEnabled()) {
				logger3.trace(bufferString);
				bruteforceEBusTelegram(telegram);
			}

			return null;
		}

		// loop thru all matching telegrams from registry
		for (Map<String, Object> registryEntry : matchedTelegramRegistry) {

			String classKey = registryEntry.containsKey("class") ? (String) registryEntry.get("class") : "";

			int debugLevel = 0;
			if(registryEntry.containsKey("debug")) {
				debugLevel = ((Long)registryEntry.get("debug")).intValue();
			}

			// get values block
			@SuppressWarnings("unchecked")
			Map<String, Map<String, Object>> values = (Map<String, Map<String, Object>>) registryEntry.get("values");

			logger2.debug("  >>> {}", registryEntry.containsKey("comment") ? 
					registryEntry.get("comment") : "<No comment available>");

			for (Entry<String, Map<String, Object>> entry : values.entrySet()) {

				String uniqueKey = (classKey != "" ? classKey + "." : "") + entry.getKey();
				settings = entry.getValue();

				String type = ((String) settings.get("type")).toLowerCase();
				int pos = settings.containsKey("pos") ? ((Long) settings.get("pos")).intValue() : -1;

				//				BigDecimal valueMin = settings.containsKey("min") ? BigDecimal.valueOf(((Number) settings.get("min")).doubleValue()) : null;
				//				BigDecimal valueMax = settings.containsKey("max") ? BigDecimal.valueOf(((Number) settings.get("max")).doubleValue()) : null;
				//				BigDecimal replaceValue = settings.containsKey("replaceValue") ? BigDecimal.valueOf(((Number) settings.get("replaceValue")).doubleValue()) : null;


				BigDecimal valueMin = NumberUtils.toBigDecimal(settings.get("min"));
				BigDecimal valueMax = NumberUtils.toBigDecimal(settings.get("max"));
				BigDecimal replaceValue = NumberUtils.toBigDecimal(settings.get("replaceValue"));
				BigDecimal factor = NumberUtils.toBigDecimal(settings.get("factor"));

				Object value = getValue(byteBuffer, type, pos,
						valueMin, valueMax, replaceValue, factor);


				// Add global variables thisValue and keyName to JavaScript context
				HashMap<String, Object> bindings = new HashMap<String, Object>();
				bindings.put(entry.getKey(), value);
				bindings.put(uniqueKey, value);
				bindings.put("thisValue", value);

				if(settings.containsKey("cscript")) {
					try {
						value = evaluateScript(entry, bindings);
					} catch (ScriptException e) {
						logger.error("Error on evaluating JavaScript!", e);
						break;
					}
				}

				String label = (String) (settings.containsKey("label") ? settings.get("label") : "");
				String format = String.format("%-35s%-10s%s", uniqueKey, value, label);
				if(debugLevel >= 2) {
					logger2.debug("    >>> " + format);
				} else {
					logger2.trace("    >>> " + format);
				}

				valueRegistry.put(uniqueKey, value);
				valueRegistry2.put(entry.getKey(), value);
			}

			// computes values available? if not exit here
			if(!registryEntry.containsKey("computed_values"))
				continue;

			@SuppressWarnings("unchecked")
			Map<String, Map<String, Object>> cvalues = (Map<String, Map<String, Object>>) registryEntry.get("computed_values");
			for (Entry<String, Map<String, Object>> entry : cvalues.entrySet()) {
				String uniqueKey = (classKey != "" ? classKey + "." : "") + entry.getKey();
				HashMap<String, Object> bindings = new HashMap<String, Object>();
				bindings.putAll(valueRegistry);
				bindings.putAll(valueRegistry2);
				Object value;
				try {
					value = evaluateScript(entry, bindings);
					valueRegistry.put(entry.getKey(), value);

					if(debugLevel >= 2) {
						String label = (String) (settings.containsKey("label") ? settings.get("label") : "");
						String format = String.format("%-35s%-10s%s", uniqueKey, value, label);
						logger2.debug("    >>> " + format);
					}

				} catch (ScriptException e) {
					logger.error("Error on evaluating JavaScript!", e);
				}
			}
		}

		return valueRegistry;
	}

}