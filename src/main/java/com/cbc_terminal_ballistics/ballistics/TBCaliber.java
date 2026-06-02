package com.cbc_terminal_ballistics.ballistics;

public enum TBCaliber {
    // penetrationScale is currently retained for config/API compatibility but is not used for
    // the penetrate/stop decision, which follows CBC mass/momentum vs block toughness.
    // integrityDamageScale: permanent armor fatigue;
    // massLossScale: how quickly projectiles are bled off across layers; spallScale: AP spall output.
    // Autocannon AP is allowed to keep flying after light masonry (~10 toughness/resistance),
    // while cannon calibers lose noticeably more energy per plate to reduce over-penetration.
    AUTOCANNON(1.45, 0.04, 0.25, 0.2),
    SMALL(0.45, 0.18, 1.15, 0.65),
    SMALL_MEDIUM(0.60, 0.25, 1.35, 0.85),
    MEDIUM(0.70, 0.32, 1.55, 1.05),
    BIG(0.78, 0.45, 1.75, 1.5);

    public final double penetrationScale;
    public final double integrityDamageScale;
    public final double massLossScale;
    public final double spallScale;

    TBCaliber(double penetrationScale, double integrityDamageScale, double massLossScale, double spallScale) {
        this.penetrationScale = penetrationScale;
        this.integrityDamageScale = integrityDamageScale;
        this.massLossScale = massLossScale;
        this.spallScale = spallScale;
    }
}
