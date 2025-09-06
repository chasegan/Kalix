import com.andrewauclair.moderndocking.Docking;
import com.andrewauclair.moderndocking.DockingSettings;
import com.andrewauclair.moderndocking.persist.AppStatePersister;
import com.andrewauclair.moderndocking.persist.NoPersistence;
import com.andrewauclair.moderndocking.ui.DockingFrame;

import javax.swing.*;
import java.awt.*;

public class HelloWorldDocking {

    public static void main(String[] args) {
        // Initialize Docking settings and persistence
        DockingSettings.setPersister(new NoPersistence()); // No persistence for this simple example

        // Create the main frame
        JFrame frame = new JFrame("Hello World Docking");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        // Create the DockingFrame, which manages the dockable components
        DockingFrame dockingFrame = new DockingFrame(frame);
        frame.setContentPane(dockingFrame);

        // Initialize the Docking system with the frame
        Docking.initialize(frame);

        // Create some simple dockable panels
        JPanel panel1 = new JPanel();
        panel1.setBackground(Color.RED);
        panel1.add(new JLabel("Panel 1"));
        Docking.registerDockable(panel1, "panel1", "Panel One");

        JPanel panel2 = new JPanel();
        panel2.setBackground(Color.BLUE);
        panel2.add(new JLabel("Panel 2"));
        Docking.registerDockable(panel2, "panel2", "Panel Two");

        // Display the dockables
        Docking.display(panel1);
        Docking.display(panel2);

        // Make the frame visible
        frame.setVisible(true);
    }
}