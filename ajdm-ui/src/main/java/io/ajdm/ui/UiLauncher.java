package io.ajdm.ui;

/** Separate launcher avoids the Java launcher's module-only Application special case. */
public final class UiLauncher {
    private UiLauncher() { }
    public static void main(String[] args) { javafx.application.Application.launch(AjdmApp.class, args); }
}
