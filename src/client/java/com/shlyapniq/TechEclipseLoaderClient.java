package com.shlyapniq;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Клиентский инициализатор мода.
 *
 * Что делает:
 * - Ждёт, пока Minecraft покажет TitleScreen (главное меню)
 * - Перехватывает его и открывает наш UpdateScreen
 * - Это происходит только один раз за запуск игры
 */
public class TechEclipseLoaderClient implements ClientModInitializer {

    // Флаг: экран обновления уже был показан?
    private boolean updateScreenShown = false;

    @Override
    public void onInitializeClient() {
        // Регистрируем обработчик тика клиента.
        // Каждый тик (20 раз/сек) проверяем текущий экран.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Когда TitleScreen появляется впервые — заменяем его на UpdateScreen
            if (!updateScreenShown && client.screen instanceof TitleScreen) {
                updateScreenShown = true;
                client.setScreen(new UpdateScreen());
            }
        });

        TechEclipseLoader.LOGGER.info("TechEclipse Loader client initialized");
    }
}
