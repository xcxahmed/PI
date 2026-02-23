package org.example;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(
                getClass().getResource("/fxml/ajout_evenement.fxml")
        );
        stage.setTitle("📅 Gestion des Événements");
        stage.setScene(new Scene(root, 1200, 780));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}