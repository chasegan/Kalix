"""
Generate GR4JSG reference fixtures for Kalix's validation tests.

Oracle: `step()` below is a line-by-line transliteration of the Fors node
`GRSGDPIENode.cs` (StepFlowPhase and Initialize), itself a port of the DPIE
Source plugin `GRSG_DPIE`. It keeps the original's arithmetic, including the
cap of 13 on the tanh argument and the floor(x) + 1 unit hydrograph lengths,
so it is independent of the Rust kernel.

The parameters Kalix leaves to input expressions are held neutral: catchment
elevation equals reference elevation (no lapse) and the rainfall scaling
factor is 1.

Driving data: Rex Creek rain and PET (src/tests/fors_gr4j_model) for 20
years, with a synthetic alpine temperature series (a seasonal cycle plus
deterministic noise), which gives snowfall, dry melt and rain on snow.

Usage (standard library only):
    python3 src/tests/example_data/gr4jsg/generate_reference.py
"""
import csv
import math

SRC_DIR = "src/tests/fors_gr4j_model"
OUT_DIR = "src/tests/example_data/gr4jsg"
N_DAYS = 7305


class UnitHydrograph:
    """Fors UnitHydrograph: apportion the inflow, release day 0, shift."""

    def __init__(self, kernel):
        self.k = kernel
        self.s = [0.0] * len(kernel)

    def apply(self, inflow):
        n = len(self.k)
        for i in range(n):
            self.s[i] += inflow * self.k[i]
        outflow = self.s[0]
        for i in range(n - 1):
            self.s[i] = self.s[i + 1]
        self.s[n - 1] = 0.0
        return outflow


def sh1(t, x4):
    if t <= 0:
        return 0.0
    if t < x4:
        return math.pow(t / x4, 2.5)
    return 1.0


def sh2(t, x4):
    if t <= 0:
        return 0.0
    if t < x4:
        return 0.5 * math.pow(t / x4, 2.5)
    if t < 2 * x4:
        return 1.0 - 0.5 * math.pow(2.0 - t / x4, 2.5)
    return 1.0


class GRSGDPIE:
    def __init__(self, p):
        self.__dict__.update(p)
        self.S = 0.0
        self.R = 0.0
        self.SnowBucket = self.InitialSnowBucket
        self.IceBucket = self.InitialIceBucket
        n = int(math.floor(self.X4)) + 1
        self.UH1 = UnitHydrograph([sh1(i + 1, self.X4) - sh1(i, self.X4) for i in range(n)])
        m = int(math.floor(2.0 * self.X4)) + 1
        self.UH2 = UnitHydrograph([sh2(i + 1, self.X4) - sh2(i, self.X4) for i in range(m)])
        q = int(math.floor(2.0 * self.ReturnFlow)) + 1
        self.UH3 = UnitHydrograph([sh2(i + 1, self.ReturnFlow) - sh2(i, self.ReturnFlow) for i in range(q)])

    def step(self, InputRainfall, E0, TMax, TMin):
        dz = self.CatchmentElevation - self.ReferenceElevation
        PETFactor = self.PETlapsePerm * dz
        TMaxAdjust = self.TMaxlapsePerm * dz
        TMinAdjust = self.TMinlapsePerm * dz
        if TMin + TMinAdjust > TMax + TMaxAdjust:
            TempLapseRate = self.Tfrac * self.TMaxlapsePerm + (1.0 - self.Tfrac) * self.TMinlapsePerm
            TMaxAdjust = TempLapseRate * dz
            TMinAdjust = TempLapseRate * dz
        TMin += TMinAdjust
        TMax += TMaxAdjust
        RepTemp = self.Tfrac * TMax + (1.0 - self.Tfrac) * TMin

        ModifiedRain = max(0.0, InputRainfall * self.RainfallScalingFactor)
        newPET = max(E0 + PETFactor, 0.0)

        accumulationGain = self.FluxAccumulation * self.PercentAccumulation / 100.0
        if self.IceBucket > 0:
            self.IceBucket += accumulationGain
        else:
            self.SnowBucket += accumulationGain

        if RepTemp < self.Taccum:
            Snowfall = max(0.0, ModifiedRain)
            ModifiedRain = 0.0
            self.SnowBucket += Snowfall
        else:
            Snowfall = 0.0

        SnowMeltDepth = 0.0
        IceMeltDepth = 0.0
        if self.SnowBucket + self.IceBucket > 0.0:
            proportionOfTimeStepSnow = 0.0
            if self.SnowBucket > 0:
                if ModifiedRain > 0.0:
                    potentialSnowMeltDepth = max(0.0, RepTemp) * (self.m_rainfall + 0.0126 * ModifiedRain) + self.base_rainfall
                else:
                    beta = min(1.5, max(0.0, TMin) / 4.4)
                    potentialSnowMeltDepth = self.m_nonrainfall * (max(0.0, RepTemp) + beta * ((TMax - TMin) / 8.0 + TMin))
                SnowMeltDepth = min(potentialSnowMeltDepth, self.SnowBucket)
                self.SnowBucket -= SnowMeltDepth
                ModifiedRain += SnowMeltDepth
                unmetSnowMelt = potentialSnowMeltDepth - SnowMeltDepth
                if potentialSnowMeltDepth > 0:
                    proportionOfTimeStepSnow = 1 - (unmetSnowMelt / potentialSnowMeltDepth)
            if self.IceBucket > 0:
                if RepTemp > self.Tmelt:
                    proportionOfTimeStepIce = 1.0 - proportionOfTimeStepSnow
                    potentialIcemeltDepth = self.Ddfi * (RepTemp - self.Tmelt)
                    actualMaxIcemeltDepth = proportionOfTimeStepIce * potentialIcemeltDepth
                    IceMeltDepth = min(max(0.0, actualMaxIcemeltDepth), self.IceBucket)
                    self.IceBucket -= IceMeltDepth
                else:
                    IceMeltDepth = 0.0
        IceMeltDepth = self.UH3.apply(IceMeltDepth)

        X1, X2, X3 = self.X1, self.X2, self.X3
        Ps = 0.0
        Es = 0.0
        if ModifiedRain > newPET:
            netRainfall = ModifiedRain - newPET
            ws = netRainfall / X1
            if ws > 13.0:
                ws = 13.0
            Ps = (X1 * (1.0 - math.pow(self.S / X1, 2.0)) * math.tanh(ws)) / (1.0 + (self.S / X1) * math.tanh(ws))
            Pr = netRainfall - Ps
        else:
            netET = newPET - ModifiedRain
            ws = netET / X1
            if ws > 13.0:
                ws = 13.0
            Es = (self.S * (2.0 - self.S / X1) * math.tanh(ws)) / (1.0 + (1.0 - self.S / X1) * math.tanh(ws))
            Pr = 0.0

        self.S = self.S - Es + Ps

        Sx149 = 0.4444444444444444444444444444444444444444444444444444444444 * (self.S / X1)
        Sx149s = Sx149 * Sx149
        Perc = self.S * (1.0 - math.pow(1.0 + Sx149s * Sx149s, -0.25))
        self.S = self.S - Perc
        Pr = Perc + Pr

        Q9 = self.UH1.apply(Pr) * 0.9
        Q1 = self.UH2.apply(Pr) * 0.1

        RX3 = self.R / X3
        Rx37 = RX3 * RX3 * RX3 * RX3 * RX3 * RX3 * RX3
        ech = X2 * math.sqrt(Rx37)

        Tp = self.R + Q9 + ech
        self.R = 0.0
        if Tp >= 0.0:
            self.R = Tp

        Rx3 = self.R / X3
        Rx3s = Rx3 * Rx3
        Qr = self.R - self.R / math.pow(1.0 + Rx3s * Rx3s, 0.25)
        self.R = self.R - Qr

        Qd = 0.0
        Tp = Q1 + ech
        if Tp > 0.0:
            Qd = Q1 + ech

        return Qr + Qd + IceMeltDepth, self.SnowBucket, self.IceBucket


def read_values(path):
    with open(path) as f:
        rows = list(csv.reader(f))[1:]
    return [(r[0], float(r[1])) for r in rows[:N_DAYS]]


def temperatures(n):
    """Seasonal cycle plus deterministic noise (a linear congruential generator)."""
    state = 12345
    out = []
    for day in range(n):
        state = (1103515245 * state + 12345) % 2147483648
        noise = state / 2147483648.0 - 0.5
        state = (1103515245 * state + 12345) % 2147483648
        spread = 4.0 + 8.0 * (state / 2147483648.0)
        mean = 3.0 + 9.0 * math.sin(2.0 * math.pi * day / 365.25) + 8.0 * noise
        out.append((round(mean + spread / 2.0, 3), round(mean - spread / 2.0, 3)))
    return out


NEUTRAL = dict(
    CatchmentElevation=1.0, ReferenceElevation=1.0,
    TMinlapsePerm=-0.0065, TMaxlapsePerm=-0.0065, PETlapsePerm=-0.0005,
    RainfallScalingFactor=1.0, InitialSnowBucket=0.0,
)

CASES = {
    # Snow only: no ice, no accumulation.
    "gr4jsg_snow_reference.csv": dict(
        NEUTRAL, X1=320.0, X2=-1.5, X3=75.0, X4=1.7,
        Tfrac=0.4, Taccum=0.5, m_rainfall=3.38, base_rainfall=1.3, m_nonrainfall=2.2,
        InitialIceBucket=0.0, Ddfi=6.0, Tmelt=0.0, ReturnFlow=0.5,
        FluxAccumulation=0.0, PercentAccumulation=0.0),
    # Glacier that melts out part-way through the run (accumulation = 40 * 5 / 100 = 2 mm/day).
    "gr4jsg_glacier_reference.csv": dict(
        NEUTRAL, X1=320.0, X2=-1.5, X3=75.0, X4=1.7,
        Tfrac=0.4, Taccum=0.5, m_rainfall=3.38, base_rainfall=1.3, m_nonrainfall=2.2,
        InitialIceBucket=80000.0, Ddfi=5.0, Tmelt=-0.5, ReturnFlow=3.2,
        FluxAccumulation=40.0, PercentAccumulation=5.0),
}

rain = read_values(f"{SRC_DIR}/rex_rain.csv")
pet = read_values(f"{SRC_DIR}/rex_mpot.csv")
temps = temperatures(N_DAYS)

for name, params in CASES.items():
    model = GRSGDPIE(params)
    with open(f"{OUT_DIR}/{name}", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["timestamp", "rain_mm", "pet_mm", "tmax_c", "tmin_c", "runoff_mm", "snow_store_mm", "ice_store_mm"])
        total = 0.0
        max_snow = 0.0
        for i in range(N_DAYS):
            q, snow, ice = model.step(rain[i][1], pet[i][1], temps[i][0], temps[i][1])
            total += q
            max_snow = max(max_snow, snow)
            w.writerow([rain[i][0], rain[i][1], pet[i][1], temps[i][0], temps[i][1],
                        "%.12g" % q, "%.12g" % snow, "%.12g" % ice])
    print(f"wrote {N_DAYS} rows -> {name}: runoff sum={total:.3f} max snow={max_snow:.3f} final ice={ice:.3f}")
