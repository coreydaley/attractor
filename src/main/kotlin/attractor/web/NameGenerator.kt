package attractor.web

import kotlin.random.Random

object NameGenerator {
    private val adjectives = listOf(
        "amber", "ancient", "azure", "blazing", "breezy", "bright", "calm", "celestial",
        "cerulean", "cobalt", "coral", "cosmic", "crisp", "crystal", "cyan", "dazzling",
        "distant", "emerald", "endless", "ethereal", "flowing", "frosted", "gentle", "gilded",
        "glacial", "glowing", "golden", "grand", "hidden", "hushed", "indigo", "jade",
        "keen", "lavender", "leafy", "lofty", "lunar", "misty", "moonlit", "mossy",
        "nimble", "noble", "opal", "peaceful", "radiant", "rapid", "rosy", "rugged",
        "rustic", "sage", "sapphire", "serene", "shimmering", "silent", "silver", "sleek",
        "snowy", "solar", "still", "stormy", "sunlit", "swift", "teal", "tranquil",
        "vast", "velvet", "vibrant", "vivid", "wandering", "wispy"
    )

    private val nouns = listOf(
        "albatross", "aurora", "avalanche", "basalt", "birch", "bison", "blizzard",
        "boulder", "canyon", "cedar", "comet", "condor", "cosmos", "crane", "delta",
        "driftwood", "dune", "eagle", "eclipse", "falcon", "fjord", "flare", "fossil",
        "gazelle", "geyser", "glacier", "granite", "heron", "horizon", "jaguar", "jasper",
        "kestrel", "lagoon", "lynx", "maple", "marble", "meteor", "nebula", "nova",
        "orca", "osprey", "otter", "pebble", "prism", "quartz", "rapids", "raven",
        "reef", "ripple", "robin", "salamander", "sequoia", "solstice", "sparrow",
        "summit", "tempest", "tide", "topaz", "tortoise", "toucan", "typhoon", "vortex",
        "waterfall", "willow", "zenith", "zephyr"
    )

    fun generate(): String {
        val adj    = adjectives[Random.nextInt(adjectives.size)]
        val noun   = nouns[Random.nextInt(nouns.size)]
        val number = Random.nextInt(100, 1000)
        return "${adj.replaceFirstChar { it.uppercaseChar() }}-${noun.replaceFirstChar { it.uppercaseChar() }}-$number"
    }
}
