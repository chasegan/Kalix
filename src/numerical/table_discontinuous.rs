/// This is a continuous or discontinuous function made from PWL segments (it's still a function
/// because every x value maps to exactly 1 y value). The discontinuity means we allow adjacent
/// PWL segments to not be connected at a common y. For a function that is discontinuous at a
/// given x the value of y(x) will depend on the convention we adopt - whether we associate the
/// junction points with the lower or higher segment.
///
/// A concrete example of this is the function for propagating orders through loss nodes, where
/// x = required_ds_flow and y = required_us_flow. It is possible for this function to be
/// discontinuous in places where the loss function has a 1:1 slope (i.e. a segment where all
/// additional flow is lost).
///
/// This struct will also feature an internal state, remembering where the last solution was found
/// in the hope of leveraging the fact that subsequent lookups are likely to be nearby.

#[derive(Default)]
#[derive(Clone)]
pub struct TableDiscontinuous {
    segs: Vec<Segment>,
    x_min: f64,
    x_max: f64,
    draft_seg: Option<Segment>,
    x_max_incl_draft: f64,
    i_hint: usize, //the index of the segment that performed the last interpolation/extrapolation.
}

#[derive(Default)]
#[derive(Clone)]
struct Segment {
    xlo: f64,
    ylo: f64,
    xhi: f64,
    yhi: f64,
    m: f64,
    c: f64,
}


impl TableDiscontinuous {

    // Basic constructor
    pub fn new() -> TableDiscontinuous {
        TableDiscontinuous {
            segs: vec![],
            x_min: f64::NAN,
            x_max: f64::NAN,
            draft_seg: None,
            x_max_incl_draft: 0.0,
            i_hint: 0,
        }
    }

    /// You build a TableDiscontinuous by adding points to the table, if
    /// at any point you add the same x-value again, this method will assume
    /// you are starting a discontinuous segment by defining a different y
    /// for the same x. Otherwise, it will assume you are simply creating a
    /// new segment from the previous x_max (and its y) to the new x (and
    /// its y).
    pub fn add_point(&mut self, x: f64, y: f64) {
        if (self.segs.is_empty() && self.draft_seg.is_none()) || x == self.x_max_incl_draft {

            // We are going to create a new segment, and set it aside as a draft. Note that this
            // may replace an existing draft if the same x was submitted more than once already!
            self.draft_seg = Some(Segment { xlo: x, ylo: y, ..Default::default() });
            self.x_max_incl_draft = x;

        } else {

            // We are going to finalise a segment
            let mut seg = if self.draft_seg.is_some() {
                //The new point will finalise our (existing) draft segment.
                let mut draft = self.draft_seg.take().unwrap();
                draft.xhi = x;
                draft.yhi = y;
                draft
            } else {
                //The new point creates a whole new segment.
                let i_last = self.segs.len() - 1;
                Segment {
                    xlo: self.segs[i_last].xhi,
                    ylo: self.segs[i_last].yhi,
                    xhi: x,
                    yhi: y,
                    ..Default::default()
                }
            };
            // Update the slope and intercept and push it onto the vec
            seg.m = (seg.yhi - seg.ylo) / (seg.xhi - seg.xlo);
            seg.c = seg.ylo - seg.m * seg.xlo;
            self.segs.push(seg);

            // Update x_min and x_max
            self.x_min = self.segs.first().unwrap().xlo;
            self.x_max = self.segs.last().unwrap().xhi;
            self.x_max_incl_draft = x;
        }
    }

    pub fn is_unfinished(&self) -> bool {
        self.draft_seg.is_some()
    }

    /// After the initial insertion of points, it is possible to have an unfinished draft segment.
    /// This will happen if an x value was inserted twice (or more). Technically this means that
    /// the PWL should extrapolate to infinity, i.e. y(x>x_max) = infinity (or maybe -infinity).
    /// But in practice, e.g. in the case of mapping outflow -> inflow for ordering through losses
    /// we just want to cap it at the maximum value.
    pub fn cap_if_unfinished(&mut self) {
        if self.draft_seg.is_some() {
            // Remove the draft and create a new segment to cap the function.
            // Base this on the last defined segment.
            self.draft_seg = None;
            if self.segs.len() > 0 {
                let temp = self.segs.last().unwrap();
                let new_x = temp.xhi + (temp.xhi - temp.xlo);
                let new_y = temp.yhi;
                self.add_point(new_x, new_y);
            } else {
                // Just make the function y=x? Not sure how this would arise.
                self.add_point(0.0, 0.0);
                self.add_point(1.0, 0.0);
            }
        }
    }

    /// Number of segments
    pub fn nsegs(&self) -> usize {
        self.segs.len()
    }

    /// Interpolate within a specific segment. This does not check the bounds thus
    /// could also be leveraged to do linear extrapolation outside the range.
    pub fn interpolate_segment(&self, i: usize, xvalue: f64) -> f64 {
        let seg = &self.segs[i];
        seg.m * xvalue + seg.c
    }

    /// Binary search to find i such that self.segs[i] has xlo < xvalue <= xhi.
    pub fn find_seg_for_interpolation_or_extrapolation(&mut self, xvalue: f64) -> usize {
        if xvalue < self.x_min { return 0 };
        if xvalue > self.x_max { return self.segs.len() - 1 };

        // Use i_hint to either return immediately or narrow the binary search range
        let hint = self.i_hint;
        let (mut lo, mut hi) = if xvalue > self.segs[hint].xhi {
            (hint + 1, self.segs.len() - 1)
        } else if xvalue > self.segs[hint].xlo {
            self.i_hint = hint;
            return hint;
        } else {
            (0, hint.saturating_sub(1))
        };

        // Find i such that segs[i].xlo < xvalue <= segs[i].xhi
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            if self.segs[mid].xhi < xvalue {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        self.i_hint = lo;
        lo
    }

    /// Interpolate or extrapolate within the table.
    pub fn interpolate_or_extrapolate(&mut self, xvalue: f64) -> f64 {
        let row = self.find_seg_for_interpolation_or_extrapolation(xvalue);
        self.interpolate_segment(row, xvalue)
    }
}
