package com.infradesk.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextUndoTest {

    @Test
    void undoAndRedoTypedText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TextUndo.install();
            JPanel panel = new JPanel();
            JTextField field = new JTextField();
            panel.add(field);

            field.replaceSelection("ocid1.");
            field.replaceSelection("tenancy");
            assertEquals("ocid1.tenancy", field.getText());

            TextUndo.undo(field);
            assertEquals("ocid1.", field.getText());
            TextUndo.redo(field);
            assertEquals("ocid1.tenancy", field.getText());
        });
    }
}
