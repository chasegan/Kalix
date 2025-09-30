use crate::io::csv_io::csv_string_to_f64_vec;

#[derive(Clone, Default)]
pub struct Location {
    x: f64,
    y: f64,
}


impl Location {
    pub fn new(x: f64, y: f64) -> Self {
        Self { x, y }
    }

    pub fn from_str(s: &str) -> Result<Location, &str> {
        match csv_string_to_f64_vec(s) {
            Ok(coords) => {
                if coords.len() == 2 {
                    Ok(Location::new(coords[0], coords[1]))
                } else {
                    Err("Error parsing node location: expected 2 coordinates.")
                }
            },
            Err(_) => Err("Error parsing node location: expected 2 coordinates."),
        }
    }
    
    pub fn to_string(&self) -> String {
        format!("{},{}", self.x, self.y)
    }
}