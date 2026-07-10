package com.modmigrator;

import atlantafx.base.theme.CupertinoLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        try {
            Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());
        } catch (Exception e) {
            System.err.println("[WARN] AtlantaFX theme failed to load: " + e.getMessage());
        }

        URL fxmlUrl = getClass().getResource("/fxml/MainWindow.fxml");
        if (fxmlUrl == null) {
            throw new IllegalStateException("Cannot find /fxml/MainWindow.fxml on classpath");
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Scene scene = new Scene(loader.load(), 1100, 750);

        URL cssUrl = getClass().getResource("/css/app.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        } else {
            System.err.println("[WARN] Cannot find /css/app.css - running without custom styles");
        }

        primaryStage.setTitle("Minecraft Mod Migrator - Project Phoenix");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.setScene(scene);

        primaryStage.setOnCloseRequest(event -> {
            System.out.println("[INFO] Window closing - shutting down.");
            Platform.exit();
            System.exit(0);
        });

        primaryStage.show();
        System.out.println("[INFO] Window shown successfully.");
    }

    @Override
    public void stop() throws Exception {
        System.out.println("[INFO] Application stop() called - exiting JVM.");
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        Platform.setImplicitExit(true);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("[FATAL] Uncaught exception on thread " + t.getName());
            e.printStackTrace();
        });
        launch(args);
    }
}
