package com.kalix.ide.components;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupTriggerListenerTest {

    private final JPanel source = new JPanel();
    private final List<MouseEvent> fired = new ArrayList<>();
    private final PopupTriggerListener listener = new PopupTriggerListener(fired::add);

    private MouseEvent event(int id, boolean popupTrigger) {
        return new MouseEvent(source, id, 0L, 0, 5, 5, 1, popupTrigger, MouseEvent.BUTTON3);
    }

    @Test
    void firesWhenTheTriggerArrivesOnPress() {
        MouseEvent press = event(MouseEvent.MOUSE_PRESSED, true);
        listener.mousePressed(press);
        listener.mouseReleased(event(MouseEvent.MOUSE_RELEASED, false));

        assertEquals(List.of(press), fired);
    }

    @Test
    void firesWhenTheTriggerArrivesOnRelease() {
        MouseEvent release = event(MouseEvent.MOUSE_RELEASED, true);
        listener.mousePressed(event(MouseEvent.MOUSE_PRESSED, false));
        listener.mouseReleased(release);

        assertEquals(List.of(release), fired);
    }

    @Test
    void ignoresAnOrdinaryClick() {
        listener.mousePressed(event(MouseEvent.MOUSE_PRESSED, false));
        listener.mouseReleased(event(MouseEvent.MOUSE_RELEASED, false));
        listener.mouseClicked(event(MouseEvent.MOUSE_CLICKED, false));

        assertTrue(fired.isEmpty());
    }
}
