package com.modmigrator;

import atlantafx.base.theme.CupertinoLight;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.Objects;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        Application.setUserAgentStylesheet(new CupertinoLight().getUserAgentStylesheet());

        FXMLLoader loader = new FXMLLoader(
            getClass().getResource("/fxml/MainWindow.fxml")
        );
        Scene scene = new Scene(loader.load(), 1100, 750);
        scene.getStylesheets().add(
            Objects.requireNonNull(getClass().getResource("/css/app.css")).toExternalForm()
        );

        primaryStage.setTitle("Minecraft Mod Migrator — Project Phoenix");
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
