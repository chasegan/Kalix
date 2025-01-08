use std::fs;

/// This is a 2d table which has a fixed number of columns but may grow
/// in length. The internal data structure is a 1d vector that is wrapped
/// around to make a table.
#[derive(Default)]
#[derive(Clone)]
pub struct Table {
    ncols: usize,
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
            data: vec![],
        }
    }


    /// TODO:
    pub fn from_csv(filename: &str) -> Self {

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
        match self.find_row(xcol, xvalue) {
            None => f64::NAN,
            Some(row) => {
                self.interpolate_row(row, xcol, ycol, xvalue)
            },
        }
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


    /// Assuming the values in the column are increasing, this function finds the
    /// row index i such that data[i] <= value < data[i+1]. On the last row it
    /// also accepts values equal to the upper i.e. data[i] <= value <= data[i+1].
    /// This guarantees that valid results will have a maximum value of nrows-2.
    /// If there is no solution, the return value will be data.len()+1 which is
    /// easily recognisable to data.len() or nrows (it will be larger than both).
    pub fn find_row(&self, col: usize, value: f64) -> Option<usize> {
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
        return Some(lo);
    }


    /// Checks if a pair of functions form a monotonically increasing
    /// function. I.e. (1) x values must not decrease, (2) y values
    /// must not decrease, (3) if an x value is repeated then the
    /// y value must also be repeated.
    pub fn is_monotonically_increasing(&self, x_col: usize, y_col: usize) -> bool {
        let nrows = self.nrows();
        if nrows <= 1 {
            return true;
        }

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
        return true;
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