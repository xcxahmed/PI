package Controllers;

import Entities.Evenement;
import Services.Emailservice;
import Entities.Invitation;
import Services.EvenementService;
import Services.InvitationService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.web.WebEngine;
import netscape.javascript.JSObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Bridge Java ↔ JS pour invitation.html
 * Exposé sous window.invBridge
 */
public class InvitationBridge {

    private final InvitationService invService  = new InvitationService();
    private final EvenementService  evService   = new EvenementService();
    private static final DateTimeFormatter FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebEngine engine;
    // ✅ Référence forte — empêche le GC
    private JSObject jsWindow;

    public InvitationBridge(WebEngine engine) {
        this.engine = engine;
    }

    public void inject() {
        jsWindow = (JSObject) engine.executeScript("window");
        jsWindow.setMember("invBridge", this);
        System.out.println("✅ InvitationBridge injecté");
    }

    // ══════════════════════════════════════════════
    //  NAVIGATION → page Événements
    //  Appelé depuis JS : window.invBridge.goEvenements()
    // ══════════════════════════════════════════════
    public void goEvenements() {
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(
                        getClass().getResource("/fxml/ajout_evenement.fxml")
                );
                Parent root  = loader.load();
                Stage  stage = (Stage) Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .findFirst().orElse(null);
                if (stage != null) {
                    stage.setTitle("Investia — Événements");
                    stage.getScene().setRoot(root);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ══════════════════════════════════════════════
    //  GET ALL EVENEMENTS + INVITATIONS
    //  Retourne JSON : [ { evenement, invitations:[] }, ... ]
    // ══════════════════════════════════════════════
    public String getAllData() {
        return wrapJson(() -> {
            List<Evenement>  evs  = evService.getAll();
            List<Invitation> invs = invService.getAll();

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < evs.size(); i++) {
                Evenement ev = evs.get(i);
                sb.append("{")
                        .append("\"evenement\":{")
                        .append("\"id\":").append(ev.getId()).append(",")
                        .append("\"titre\":\"").append(esc(ev.getTitre())).append("\",")
                        .append("\"mode\":\"").append(esc(ev.getMode())).append("\",")
                        .append("\"dateDebut\":\"").append(ldt(ev.getDateDebut())).append("\",")
                        .append("\"lieu\":\"").append(esc(ev.getLieu())).append("\",")
                        .append("\"meetingLink\":\"").append(esc(ev.getMeetingLink())).append("\"")
                        .append("},")
                        .append("\"invitations\":[");

                // Invitations de cet événement
                boolean first = true;
                for (Invitation inv : invs) {
                    if (inv.getEvenementId() == ev.getId()) {
                        if (!first) sb.append(",");
                        sb.append("{")
                                .append("\"id\":").append(inv.getId()).append(",")
                                .append("\"evenementId\":").append(inv.getEvenementId()).append(",")
                                .append("\"email\":\"").append(esc(inv.getEmail())).append("\",")
                                .append("\"roleInvite\":\"").append(esc(inv.getRoleInvite())).append("\",")
                                .append("\"dateInvitation\":\"").append(ldt(inv.getDateInvitation())).append("\"")
                                .append("}");
                        first = false;
                    }
                }
                sb.append("]}");
                if (i < evs.size() - 1) sb.append(",");
            }
            return sb.append("]").toString();
        });
    }

    // ══════════════════════════════════════════════
    //  AJOUTER INVITATION + ENVOI EMAIL AUTO
    // ══════════════════════════════════════════════
    public String ajouterInvitation(int evenementId, String dateInvitation, String roleInvite, String email) {
        return wrapOk(() -> {
            Invitation inv = new Invitation(
                    evenementId,
                    LocalDateTime.parse(dateInvitation, FMT),
                    roleInvite,
                    email
            );
            invService.ajouter(inv);

            // ✅ Envoi email automatique à l'invité
            Evenement ev = evService.getById(evenementId);
            if (ev != null) {
                Emailservice.envoyerInvitation(inv, ev);
            }
        });
    }

    // ══════════════════════════════════════════════
    //  MODIFIER INVITATION
    // ══════════════════════════════════════════════
    public String modifierInvitation(int id, int evenementId, String dateInvitation, String roleInvite, String email) {
        return wrapOk(() -> {
            Invitation inv = new Invitation(
                    id, evenementId,
                    LocalDateTime.parse(dateInvitation, FMT),
                    roleInvite,
                    email
            );
            invService.modifier(inv);
        });
    }

    // ══════════════════════════════════════════════
    //  SUPPRIMER INVITATION
    // ══════════════════════════════════════════════
    public String supprimerInvitation(int id) {
        return wrapOk(() -> invService.supprimer(id));
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

    private String esc(String s) {
        if (s==null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","");
    }

    private String ldt(LocalDateTime t) { return t==null?"":t.format(FMT); }

    @FunctionalInterface interface SqlRunnable   { void run() throws Exception; }
    @FunctionalInterface interface SqlSupplier<T>{ T    get() throws Exception; }
}