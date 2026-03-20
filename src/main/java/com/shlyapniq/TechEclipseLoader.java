package com.shlyapniq;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TechEclipseLoader implements ModInitializer {
	public static final String MOD_ID = "techeclipse-loader";

	// Логгер — используется во всех классах мода для вывода в консоль и лог
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("TechEclipse Loader initialized");
	}
}