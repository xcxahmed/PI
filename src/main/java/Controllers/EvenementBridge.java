package Controllers;

import Entities.Evenement;
import Services.EvenementService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

import Services.PdfExportService;
import javafx.stage.FileChooser;
import java.io.File;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bridge Java ↔ JS pour ajoutEvenement.html.
 * Exposé sous window.javaBridge.
 */
public class EvenementBridge {

    private final EvenementService service = new EvenementService();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebEngine engine;
    // ✅ Référence forte — empêche le GC
    private JSObject jsWindow;

    public EvenementBridge(WebEngine engine) {
        this.engine = engine;
    }

    public void inject() {
        jsWindow = (JSObject) engine.executeScript("window");
        jsWindow.setMember("javaBridge", this);
        System.out.println("✅ EvenementBridge injecté");
    }

    // ══════════════════════════════════════════════
    //  NAVIGATION → page Invitations
    //  Appelé depuis JS : window.javaBridge.goInvitations()
    // ══════════════════════════════════════════════
    public void goInvitations() {
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/invitation.fxml")
                );
                Parent root  = loader.load();
                Stage  stage = (Stage) Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst().orElse(null);
                if (stage != null) {
                    stage.setTitle("Investia — Invitations");
                    stage.getScene().setRoot(root);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ══════════════════════════════════════════════
    //  OUVRIR FENÊTRE MODIFIER
    //  Appelé depuis ajoutEvenement.html :
    //  window.javaBridge.openModifierWindow(id, titre, ...)
    // ══════════════════════════════════════════════
    public void openModifierWindow(int id, String titre, int projectId, int organisateurId,
                                   String description, String mode, String dateDebut, String dateFin,
                                   String lieu, String meetingLink) {

        // onSuccess : recharge la liste dans la page principale
        Runnable onSuccess = () -> Platform.runLater(() -> {
            try {
                String json = getAll();
                engine.executeScript("renderTable(" + json + ")");
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Modifierevenementcontroller ctrl = new Modifierevenementcontroller(
                id, titre, projectId, organisateurId,
                description, mode, dateDebut, dateFin,
                lieu, meetingLink, onSuccess
        );
        ctrl.show();
    }

    // ══════════════════════════════════════════════
    //  AJOUTER
    // ══════════════════════════════════════════════
    public String ajouter(String titre, int projectId, String description,
                          String mode, String dateDebut, String dateFin,
                          String lieu, String meetingLink, int organisateurId) {
        return wrapOk(() -> {
            Evenement e = new Evenement(
                    projectId, titre, description, mode,
                    LocalDateTime.parse(dateDebut, FMT),
                    LocalDateTime.parse(dateFin,   FMT),
                    lieu, meetingLink, organisateurId
            );
            service.ajouter(e);
        });
    }

    // ══════════════════════════════════════════════
    //  EXPORT PDF
    //  Appelé depuis JS : window.javaBridge.exportPdf()
    // ══════════════════════════════════════════════
    public void exportPdf() {
        Platform.runLater(() -> {
            try {
                // FileChooser pour choisir l'emplacement de sauvegarde
                FileChooser fc = new FileChooser();
                fc.setTitle("Enregistrer le rapport PDF");
                fc.setInitialFileName("evenements_investia.pdf");
                fc.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Fichier PDF", "*.pdf")
                );

                Stage stage = (Stage) Window.getWindows().stream()
                        .filter(Window::isShowing).findFirst().orElse(null);

                File file = fc.showSaveDialog(stage);
                if (file == null) return; // Annulé par l'utilisateur

                // Générer le PDF
                PdfExportService.generer(service.getAll(), file.getAbsolutePath());

                // Notifier le HTML — succès
                engine.executeScript("onPdfExported('OK', '" +
                        file.getAbsolutePath().replace("\\", "/").replace("'", "\'") + "')");

            } catch (Exception e) {
                e.printStackTrace();
                engine.executeScript("onPdfExported('ERROR', '" + e.getMessage() + "')");
            }
        });
    }

    // ══════════════════════════════════════════════
    //  SUPPRIMER
    // ══════════════════════════════════════════════
    public String supprimer(int id) {
        return wrapOk(() -> service.supprimer(id));
    }

    // ══════════════════════════════════════════════
    //  GET ALL
    // ══════════════════════════════════════════════
    public String getAll() {
        return wrapJson(() -> {
            List<Evenement> list = service.getAll();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                Evenement e = list.get(i);
                sb.append("{")
                        .append("\"id\":").append(e.getId()).append(",")
                        .append("\"projectId\":").append(e.getProjectId()).append(",")
                        .append("\"organisateurId\":").append(e.getOrganisateurId()).append(",")
                        .append("\"titre\":\"").append(escape(e.getTitre())).append("\",")
                        .append("\"description\":\"").append(escape(e.getDescription())).append("\",")
                        .append("\"mode\":\"").append(escape(e.getMode())).append("\",")
                        .append("\"lieu\":\"").append(escape(e.getLieu())).append("\",")
                        .append("\"meetingLink\":\"").append(escape(e.getMeetingLink())).append("\",")
                        .append("\"dateDebut\":\"").append(ldt(e.getDateDebut())).append("\",")
                        .append("\"dateFin\":\"").append(ldt(e.getDateFin())).append("\"")
                        .append("}");
                if (i < list.size() - 1) sb.append(",");
            }
            return sb.append("]").toString();
        });
    }

    // ══════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════
    private String wrapOk(SqlRunnable fn) {
        try { fn.run(); return "OK"; }
        catch (Exception e) { e.printStackTrace(); return "ERROR:" + (e.getMessage()==null?"UNKNOWN":e.getMessage()); }
    }

    private String wrapJson(SqlSupplier<String> fn) {
        try { String o = fn.get(); return o==null?"[]":o; }
        catch (Exception e) { e.printStackTrace(); return "[]"; }
    }

    private String escape(String s) {
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    private String ldt(LocalDateTime t) { return t==null?"":t.format(FMT); }

    @FunctionalInterface interface SqlRunnable   { void run() throws Exception; }
    @FunctionalInterface interface SqlSupplier<T>{ T    get() throws Exception; }
}