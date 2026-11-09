package net.talaatharb.imagegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;
import com.google.inject.Injector;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import net.talaatharb.imagegen.config.AppModule;
import net.talaatharb.imagegen.config.AppSettings;
import net.talaatharb.imagegen.mcp.ImageGenerationMcpServer;
import net.talaatharb.imagegen.model.ModelStore;
import net.talaatharb.imagegen.ui.TaskRunner;

@EnabledIfSystemProperty(named = "imagegen.integration", matches = "true")
class TrayIntegrationTest {
    @TempDir Path dir;

    private static <T> T fx(Callable<T> work) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(work.call());
            } catch (Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    @Test
    void trayControlsShareWindowStateAndOpenSettings() throws Exception {
        assumeTrue(SystemTray.isSupported());
        AppSettings settings = new AppSettings(dir.resolve("settings.properties"));
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            settings.setMcpConfiguration(socket.getLocalPort(), "sd15-lcm");
        }
        Injector injector = Guice.createInjector(new AppModule(ModelStore.defaultRoot(), settings));
        CompletableFuture<Void> started = new CompletableFuture<>();
        Platform.startup(() -> started.complete(null));
        started.get(10, TimeUnit.SECONDS);
        ApplicationTray tray = fx(() -> {
            FXMLLoader loader = new FXMLLoader(ImageGenApp.class.getResource("ui/main.fxml"));
            loader.setControllerFactory(injector::getInstance);
            Stage stage = new Stage();
            stage.setScene(new Scene(loader.load(), 1280, 820));
            stage.show();
            ApplicationTray installed = new ApplicationTray(stage, loader.getController());
            installed.install();
            return installed;
        });
        try {
            PopupMenu[] menu = new PopupMenu[1];
            EventQueue.invokeAndWait(() -> {
                for (java.awt.TrayIcon icon : SystemTray.getSystemTray().getTrayIcons()) {
                    if ("Image Gen Studio".equals(icon.getToolTip())) {
                        menu[0] = icon.getPopupMenu();
                    }
                }
            });
            CheckboxMenuItem toggle = (CheckboxMenuItem) menu[0].getItem(2);
            MenuItem configure = menu[0].getItem(3);
            EventQueue.invokeAndWait(() -> {
                assertFalse(toggle.getState());
                toggle.setState(true);
                for (var listener : toggle.getItemListeners()) {
                    listener.itemStateChanged(new ItemEvent(toggle, ItemEvent.ITEM_STATE_CHANGED,
                            toggle, ItemEvent.SELECTED));
                }
            });
            assertTrue(fx(() -> injector.getInstance(ImageGenerationMcpServer.class).isRunning()));
            EventQueue.invokeAndWait(() -> {
                assertTrue(toggle.getState());
                assertFalse(configure.isEnabled());
            });
            fx(() -> {
                Stage stage = (Stage) Window.getWindows().stream().filter(w -> w instanceof Stage).findFirst().orElseThrow();
                ((ToggleButton) stage.getScene().lookup("#mcpToggle")).fire();
                return null;
            });
            EventQueue.invokeAndWait(() -> {
                assertFalse(toggle.getState());
                assertTrue(configure.isEnabled());
                for (var listener : configure.getActionListeners()) {
                    listener.actionPerformed(new ActionEvent(configure, ActionEvent.ACTION_PERFORMED, "settings"));
                }
            });
            fx(() -> {
                DialogPane pane = (DialogPane) Window.getWindows().stream()
                        .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance).findFirst().orElseThrow();
                assertEquals("MCP server settings", ((Stage) pane.getScene().getWindow()).getTitle());
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
                return null;
            });
        } finally {
            fx(() -> {
                tray.close();
                injector.getInstance(ImageGenerationMcpServer.class).close();
                injector.getInstance(TaskRunner.class).shutdown();
                Platform.exit();
                return null;
            });
        }
    }
}
