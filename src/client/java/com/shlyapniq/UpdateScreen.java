package com.shlyapniq;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Кастомный GUI экран обновления модпака.
 *
 * Показывает:
 * - Заголовок "TechEclipse Modpack Updater"
 * - Текущий статус обновления (checking, downloading, installing...)
 * - Кнопку "Exit Game" (если обновление установлено) или "Continue" (если всё актуально)
 *
 * Проверка обновлений запускается в фоновом потоке, чтобы не замораживать игру.
 */
public class UpdateScreen extends Screen {

    // Менеджер обновлений — вся логика скачивания и установки
    private final UpdateManager updateManager;

    // Флаги состояния экрана
    private boolean updateStarted = false;  // Проверка запущена?
    private boolean buttonShown = false;    // Кнопка уже показана?

    public UpdateScreen() {
        super(Component.literal("TechEclipse Лоадер"));
        // Создаём UpdateManager с путём к папке .minecraft
        this.updateManager = new UpdateManager(FabricLoader.getInstance().getGameDir());
    }

    /**
     * Вызывается при инициализации экрана (и при изменении размера окна).
     * Здесь запускаем фоновый поток для проверки обновлений.
     */
    @Override
    protected void init() {
        super.init();

        // Сбрасываем флаг кнопки (init может вызываться повторно при resize)
        buttonShown = false;

        // Запускаем проверку только один раз
        if (!updateStarted) {
            updateStarted = true;

            // Создаём фоновый поток для обновления
            // daemon=true — поток не будет мешать закрытию игры
            Thread thread = new Thread(
                    updateManager::checkAndUpdate,
                    "TechEclipse-Updater"
            );
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Вызывается каждый игровой тик (20 раз в секунду).
     * Здесь проверяем, завершилось ли обновление, и добавляем кнопку.
     */
    @Override
    public void tick() {
        super.tick();

        // Когда обновление завершено — звук, фокус окна, кнопка
        if (updateManager.isFinished() && !buttonShown) {
            buttonShown = true;

            // Звук XP орба
            this.minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f)
            );

            // Выводим окно Minecraft на передний план
            long window = this.minecraft.getWindow().getWindow();
            GLFW.glfwRequestWindowAttention(window);
            GLFW.glfwFocusWindow(window);

            if (updateManager.isUpdateInstalled()) {
                // Обновление установлено → кнопка "Exit Game"
                // Игрок должен перезапустить игру, чтобы новые моды загрузились
                addRenderableWidget(Button.builder(
                        Component.literal("Выйти из игры (требуется перезапуск)"),
                        button -> this.minecraft.stop()
                ).bounds(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
            } else {
                // Всё актуально или произошла ошибка → возврат в главное меню
                if (updateManager.isUpToDate()) {
                    this.minecraft.getToasts().addToast(
                            new UpdateToast(
                                    Component.literal("TechEclipse"),
                                    Component.literal("Сборка актуальна! v"+updateManager.getLocalVersion())
                            )
                    );
                }
                this.minecraft.setScreen(new TitleScreen());
            }
        }
    }

    /**
     * Рисует экран каждый кадр.
     * super.render() рисует фон и виджеты (кнопки), мы добавляем свой текст.
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Рисуем стандартный фон и виджеты
        super.render(graphics, mouseX, mouseY, partialTick);

        // Заголовок (белый текст, по центру)
        graphics.drawCenteredString(
                this.font,
                "TechEclipse Лоадер - Обновление модпака",
                this.width / 2,
                this.height / 2 - 50,
                0xFFFFFF
        );

        // Текущий статус обновления (серый текст, по центру)
        graphics.drawCenteredString(
                this.font,
                updateManager.getStatus(),
                this.width / 2,
                this.height / 2 - 10,
                0xAAAAAA
        );

        // Зелёный ползунок прогресса
        float progress = updateManager.getProgress();
        if (progress >= 0f) {
            int barWidth = 200;
            int barHeight = 5;
            int barX = this.width / 2 - barWidth / 2;
            int barY = this.height / 2 + 8;

            // Фон полоски (тёмно-серый)
            graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

            // Заполненная часть (зелёный)
            int fillWidth = (int) (barWidth * Math.min(progress, 1f));
            if (fillWidth > 0) {
                graphics.fill(barX, barY, barX + fillWidth, barY + barHeight, 0xFF00CC00);
            }

            // Процент
            String percent = (int) (Math.min(progress, 1f) * 100) + "%";
            graphics.drawCenteredString(this.font, percent, this.width / 2, barY + barHeight + 4, 0xAAAAAA);
        }
    }

    /**
     * Можно ли закрыть экран нажатием Escape?
     * Нет — пока идёт обновление. Да — когда всё завершено.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        return updateManager.isFinished();
    }

    /**
     * Что происходит при закрытии экрана (Esc).
     * Возвращаемся на TitleScreen.
     */
    @Override
    public void onClose() {
        this.minecraft.setScreen(new TitleScreen());
    }
}
