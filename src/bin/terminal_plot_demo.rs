//! Terminal plot demonstration
//!
//! Run with: cargo run --bin terminal_plot_demo

use kalix::terminal_plot::*;

fn main() {
    println!("Terminal Plot Demo - Electric Grid Style\n");

    // Create a plot with the electric grid color scheme
    let mut plot = TerminalPlot::builder()
        .title("KALIX//OPTIMISER")
        .x_label("evals")
        .y_label("Objective Function")
        .width(50)
        .height(12)
        .x_range(0.0, 5000.0)
        .y_range(0.5, 2.5)
        .color_scheme(ColorScheme::electric_grid())
        .build();

    // Add progress bar
    plot.set_progress(4500, 5000);

    // Create best evolution line (decreasing over time - minimization)
    let best_line_points = vec![
        (0.0, 2.3),
        (500.0, 1.8),
        (1000.0, 1.5),
        (1500.0, 1.2),
        (2000.0, 1.0),
        (2500.0, 0.85),
        (3000.0, 0.75),
        (3500.0, 0.65),
        (4000.0, 0.58),
        (4500.0, 0.534),
        (4891.0, 0.523),
    ];

    plot.add_line(Line {
        points: best_line_points.clone(),
        style: LineStyle::Dots,
        color: Some(Color::BrightMagenta),
    });

    // Add current generation scatter points (worse than best)
    let current_gen = vec![
        ScatterPoint { x: 4500.0, y: 1.2, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4520.0, y: 0.9, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4540.0, y: 1.5, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4560.0, y: 0.8, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4580.0, y: 1.8, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4600.0, y: 1.1, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4620.0, y: 2.1, color: Some(Color::BrightYellow), symbol: '∘' },
        ScatterPoint { x: 4640.0, y: 0.7, color: Some(Color::BrightYellow), symbol: '∘' },
    ];

    plot.add_scatter_points(current_gen);

    // Add best marker
    plot.add_marker(Marker {
        x: 4891.0,
        y: 0.523,
        symbol: '★',
        color: Some(Color::BrightGreen),
        label: Some(format!("← BEST: {:.3}", 0.523)),
    });

    // Render the plot
    println!("{}", plot.render());

    println!("\n\nDemo of animation (clear and redraw):");
    println!("In your optimizer, use plot.clear_and_render() to update the plot in place.");

    // Show a simple animation example
    println!("\nSimulating 3 updates...\n");

    for i in 1..=3 {
        std::thread::sleep(std::time::Duration::from_millis(800));

        let mut anim_plot = TerminalPlot::builder()
            .title("KALIX//OPTIMISER")
            .x_label("evals")
            .y_label("Objective Function")
            .width(50)
            .height(12)
            .x_range(0.0, 5000.0)
            .y_range(0.5, 2.5)
            .color_scheme(ColorScheme::electric_grid())
            .build();

        let current = 1000 * i;
        anim_plot.set_progress(current, 5000);

        // Build up the line progressively
        let mut progressive_points = vec![];
        for &(x, y) in &best_line_points {
            if x <= current as f64 {
                progressive_points.push((x, y));
            }
        }

        if !progressive_points.is_empty() {
            anim_plot.add_line(Line {
                points: progressive_points,
                style: LineStyle::Dots,
                color: Some(Color::BrightMagenta),
            });
        }

        print!("{}", anim_plot.render());
    }

    println!("\n✓ Demo complete!");
}
