PROFILE_ALIASES = {
    "casual": "casual",
    "easy": "casual",
    "medium": "medium",
    "standard": "medium",
    "hard": "hard",
    "mandala": "mandala",
}

PROFILE_CHOICES = sorted(PROFILE_ALIASES.keys())

PLAYABILITY_PROFILE_THRESHOLDS = {
    "casual": {
        "max_tiny_pct_lt_100": 35,
        "max_tiny_pct_lt_200": 50,
        "max_hidden_label_pct": 70,
        "max_region_count": 600,
        "max_largest_region_pct": 50,
        "many_tiny_lt_50_ratio": 0.25,
    },
    "medium": {
        "max_tiny_pct_lt_100": 45,
        "max_tiny_pct_lt_200": 60,
        "max_hidden_label_pct": 80,
        "max_region_count": 800,
        "max_largest_region_pct": 60,
        "many_tiny_lt_50_ratio": 0.35,
    },
    "hard": {
        "max_tiny_pct_lt_100": 60,
        "max_tiny_pct_lt_200": 75,
        "max_hidden_label_pct": 90,
        "max_region_count": 1200,
        "max_largest_region_pct": 70,
        "many_tiny_lt_50_ratio": 0.50,
    },
    "mandala": {
        "max_tiny_pct_lt_100": 70,
        "max_tiny_pct_lt_200": 85,
        "max_hidden_label_pct": 95,
        "max_region_count": 1600,
        "max_largest_region_pct": 75,
        "many_tiny_lt_50_ratio": 0.60,
    },
}


def normalize_profile(profile):
    return PROFILE_ALIASES.get((profile or "casual").lower(), "casual")


def profile_thresholds(profile):
    return PLAYABILITY_PROFILE_THRESHOLDS[normalize_profile(profile)]
