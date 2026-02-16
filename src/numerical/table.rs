use std::fs;
use crate::misc::misc_functions::starts_with_numeric_char;

/// This is a 2d table which has a fixed number of columns but may grow
/// in length. The internal data structure is a 1d vector that is wrapped
/// around to make a table.
#[derive(Default)]
#[derive(Clone)]
pub struct Table {
    ncols: usize,
    col_names: Vec<String>,
    data: Vec<f64>,
}

impl Table {

    /// Create an empty table
    /// This is used in (for example) the storage node. I'm hiding the implementation here
    /// rather than using a Vec<Vec<>> or Array2d directly, because I don't really know what
    /// the best choice is.
    pub fn new(ncols: usize) -> Self {
        Self {
            ncols,
            col_names: vec![],
            data: vec![],
        }
    }


    // TODO: docs
    pub fn from_csv_string(s: &str, ncols: usize, force_read_header: bool) -> Result<Self, String> {

        let mut answer = Self::new(ncols);

        // Split the string at all the commas
        let ss = s.trim_end_matches(|c: char| c == ',' || c.is_whitespace())
            .split(',').map(|x| x.trim()).collect::<Vec<&str>>();

        // Check that there the right number of elements
        let n_elements = ss.len();
        if (n_elements % ncols) != 0 {
            return Err(format!("Number of elements must be divisible by {}", ncols));
        }

        // If the first element starts with a non-numeric char, then parse ncol header elements
        let mut i = 0;
        if !starts_with_numeric_char(&ss[0]) || force_read_header {
            while i < ncols {
                answer.col_names.push(ss[i].to_string());
                i += 1;
            }
        }

        // Parse the values into a table
        let mut row = 0;
        let mut col = 0;
        while i < n_elements {
            //Parse the element at location i
            let value = ss[i].parse::<f64>().expect("Error parsing number in table");

            //Put it into the table
            answer.set_value(row, col, value);

            //Move to the next element
            i += 1;
            col = i % ncols;
            if col == 0 {
                row += 1;
            }
        }

        // Return
        Ok(answer)
    }


    // TODO: docs
    pub fn from_csv_file(filename: &str) -> Self {

        // Read the csv file into some sort of structure.
        let csv_string = fs::read_to_string(filename).expect("Unable to read file");

        let mut ncols = 0;
        let mut answer = Self::new(0);
        let mut reader = csv::Reader::from_reader(csv_string.as_bytes());

        let mut row = 0;
        for record in reader.records() {

            // N.B. it looks like "records" already excludes the header row. The header must be
            // retained somewhere else.
            let record = record.unwrap();

            // Need to create the table?
            if ncols == 0 {
                if record.len() > ncols {
                    ncols = record.len();
                    answer = Self::new(ncols);
                }
            }

            // Read the values into the data array
            let mut col = 0;
            for field in record.iter() {
                let val = field.trim().parse::<f64>().unwrap();
                answer.set_value(row, col, val);
                col += 1;
            }
            row += 1;
        }
        answer
    }


    /// Creates a new vector populated with values of the table
    /// row by row.
    pub fn get_values_as_vec(&self) -> Vec<f64> {
        let mut answer = Vec::new();
        for row in 0..self.nrows() {
            for col in 0..self.ncols() {
                let v = self.get_value(row, col);
                answer.push(v);
            }
        }
        answer
    }


    /// Get a single value out of the table.
    pub fn get_value(&self, row: usize, col: usize) -> f64 {
        let i = row * self.ncols + col;
        self.data[i]
    }


    /// Set a single value in the table. This can cause the table to
    /// grow if the table doesn't have enough rows.
    pub fn set_value(&mut self, row: usize, col: usize, value: f64) {
        if col >= self.ncols { panic!("Attempted to access column {} but table width is just {}", col, self.ncols); }

        // Grow the vec if needed
        let len_at_row_end = (row + 1) * self.ncols;
        while self.data.len() < len_at_row_end {
            self.data.push(f64::NAN);
        }

        // Set the value
        let i = row * self.ncols + col;
        self.data[i] = value;
    }


    /// Interpolate within the table, assuming that the xcolumn is ascending.
    pub fn interpolate(&self, xcol: usize, ycol: usize, xvalue: f64) -> f64 {
        match self.find_row_for_interpolation(xcol, xvalue) {
            None => f64::NAN,
            Some(row) => {
                self.interpolate_row(row, xcol, ycol, xvalue)
            },
        }
    }


    /// Interpolate or extrapolate within the table, assuming that the xcolumn is ascending.
    pub fn interpolate_or_extrapolate(&self, xcol: usize, ycol: usize, xvalue: f64) -> f64 {
        let row = self.find_row_for_interpolation_or_extrapolation(xcol, xvalue);
        self.interpolate_row(row, xcol, ycol, xvalue)
    }


    /// Interpolate within a specific row. This does not check the bounds thus
    /// could also be leveraged to do linear extrapolation outside the range.
    pub fn interpolate_row(&self, row: usize, xcol: usize, ycol: usize, xvalue: f64) -> f64 {
        let xlo = self.get_value(row, xcol);
        let xhi = self.get_value(row + 1, xcol);
        let ylo = self.get_value(row, ycol);
        let yhi = self.get_value(row + 1, ycol);

        // Guard against rows with dx=0
        if xlo == xhi { return ylo; }

        // Interpolate
        ylo + (xvalue - xlo) * (yhi - ylo)/(xhi - xlo)
    }


    /// Gets the number of rows in the table. This is the number of elements in
    /// the data divided by the ncols.
    pub fn nrows(&self) -> usize {
        self.data.len() / self.ncols
    }


    /// Gets the number of cols in the table.
    pub fn ncols(&self) -> usize {
        self.ncols
    }


    /// Assuming the values in the column are increasing, this function finds the
    /// row index i such that data[i] <= value < data[i+1]. On the last row it
    /// also accepts values equal to the upper i.e. data[i] <= value <= data[i+1].
    /// This guarantees that valid results will have a maximum value of nrows-2.
    /// This "for_interpolation" version returns None if the value is outside the
    /// range of the table.
    pub fn find_row_for_interpolation(&self, col: usize, value: f64) -> Option<usize> {
        let nrows = self.nrows();
        if value < self.get_value(0, col) { return None; }
        if value > self.get_value(nrows-1, col) { return None; }

        // Find the row by binary search
        let mut lo = 0;
        let mut hi = nrows - 1;
        while  lo < hi - 1 {
            let mid = (lo + hi) / 2;
            let mid_value = self.get_value(mid, col);
            if value < mid_value {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        Some(lo)
    }


    /// Assuming the values in the column are increasing, this function finds the
    /// row index i such that data[i] <= value < data[i+1]. On the last row it
    /// also accepts values equal to the upper i.e. data[i] <= value <= data[i+1].
    /// This "for_interpolation_or_extrapolation" version chooses the first (last)
    /// row if value is before (after) the table range.
    pub fn find_row_for_interpolation_or_extrapolation(&self, col: usize, value: f64) -> usize {
        let nrows = self.nrows();
        if value < self.get_value(0, col) { 0; }
        if value > self.get_value(nrows-2, col) {
            // Instead of comparing to the very last value, data[nrows-1], we
            // might as well compare to data[nrows-2] since any value larger
            // than this should be interpolating/extrapolating using the last
            // segment.
            return nrows - 2;
        }

        // Find the row by binary search
        let mut lo = 0;
        let mut hi = nrows - 1;
        while  lo < hi - 1 {
            let mid = (lo + hi) / 2;
            let mid_value = self.get_value(mid, col);
            if value < mid_value {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        lo
    }


    /// Checks if a pair of functions form a monotonically increasing
    /// function. I.e. (1) x values must not decrease, (2) y values
    /// must not decrease, (3) if an x value is repeated then the
    /// y value must also be repeated.
    pub fn is_monotonically_increasing(&self, x_col: usize, y_col: usize) -> bool {
        let nrows = self.nrows();
        if nrows > 1 {
            let mut prev_x = self.get_value(0, x_col);
            let mut prev_y = self.get_value(0, y_col);
            for i in 1..nrows {
                let x = self.get_value(i, x_col);
                let y = self.get_value(i, y_col);

                // Values must not decrease
                if x < prev_x || y < prev_y { return false; }

                // Any given x must have a unique value for y
                if x == prev_x && y != prev_y { return false; }

                prev_x = x;
                prev_y = y;
            }
        }
        true
    }


    /// This is just supposed to print out the table for debugging.
    pub fn print(&self) {
        for i in 0..self.data.len() {
            let row = i / self.ncols;
            let col = i % self.ncols;
            println!("value [{}, {}] = {}", row, col, self.get_value(row, col));
        }
    }
}