package com.bettolini.financialcockpit.domain

data class ClassificationResult(
    val vaultKey: String,
    val subTag: String,
    val cleanMerchant: String
)

object MerchantClassifier {

    private data class Rule(
        val keywords: List<String>,
        val vaultKey: String,
        val subTag: String
    )

    private val rules = listOf(
        // 1. Fixed Overhead & Mandatory Debits
        Rule(
            keywords = listOf("PL-AC", "LOAN", "FINANCE", "EMI"),
            vaultKey = "FIXED_OVERHEAD",
            subTag = "#Auto Loan"
        ),
        Rule(
            keywords = listOf("DEWA", "EMPOWER", "LOOTAH", "ETISALAT", "E&", "DU ", "DU-", "VIRGIN MOBILE", "TAQA"),
            vaultKey = "FIXED_OVERHEAD",
            subTag = "#Utilities"
        ),
        Rule(
            keywords = listOf(
                "FITNESS", "GYM", "WAREHOUSE", "FITNGLAM", "BARRY", "CRANK", "STUDIO",
                "APPLE.COM", "GOOGLE", "SPOTIFY", "NETFLIX", "CHATGPT", "OPENAI",
                "CLAUDE", "GITHUB", "ADOBE", "ICLOUD", "SALIK"
            ),
            vaultKey = "FIXED_OVERHEAD",
            subTag = "#Bills & Subs"
        ),

        // 2. Rent
        Rule(
            keywords = listOf("RENT", "EJARI", "LANDLORD", "CHEQUE", "REAL ESTATE", "PROPERTY"),
            vaultKey = "RENT",
            subTag = "#Rent"
        ),

        // 3. Sustenance & Fleet (Fuel, Maintenance, Groceries, Clean Nutrition)
        Rule(
            keywords = listOf("ENOC", "EPPCO", "ADNOC", "EMARAT", "CAFU", "PETROL", "FUEL"),
            vaultKey = "SUSTENANCE_FLEET",
            subTag = "#Fuel"
        ),
        Rule(
            keywords = listOf(
                "AUTOPRO", "TASJEEL", "SHAMIL", "TYRE", "TIRE", "MICHELIN", "BRIDGESTONE",
                "BOSCH", "CORSA", "GARAGE", "MOTOR", "AUTO", "CAR WASH", "DETAILING",
                "KTM", "CORVETTE", "JEEP", "RTA", "NOL", "PARKING", "MPARKING", "CAREEM", "UBER", "HALA"
            ),
            vaultKey = "SUSTENANCE_FLEET",
            subTag = "#Fleet & Transit"
        ),
        Rule(
            keywords = listOf(
                "INSTASHOP", "GRANDIOSE", "LULU", "CARREFOUR", "SPINNEYS", "WAITROSE",
                "CHOITHRAMS", "UNION COOP", "VIVA", "KIBSONS", "TALABAT MART", "NOON MINUTES",
                "ORGANIC FOODS", "AL MAYA", "WEST ZONE", "GEANT", "ZOOM", "CIRCLE K",
                "LIFE PHARMACY", "BIN SINA", "ASTER", "BOOTS", "MYPROTEIN", "SPORTER", "IHERB", "GNC"
            ),
            vaultKey = "SUSTENANCE_FLEET",
            subTag = "#Groceries"
        ),

        // 4. Travel
        Rule(
            keywords = listOf(
                "EMIRATES", "FLYDUBAI", "ETIHAD", "WIZZ", "AIR ARABIA", "BOOKING.COM",
                "AGODA", "AIRBNB", "MARRIOTT", "HILTON", "ROTANA", "ACCOR", "HOTEL",
                "RESORT", "DUTY FREE", "DXB", "OMAN", "VFS"
            ),
            vaultKey = "TRAVEL",
            subTag = "#Travel"
        ),

        // 5. Discretionary: Nightlife, Bars, Beach Clubs & Lounges
        Rule(
            keywords = listOf(
                "PACHA", "ICON", "GIANPULA", "SOHO GARDEN", "WHITE DUBAI", "BLU DUBAI",
                "SKY2.0", "1OAK", "1 OAK", "ARMANI PRIVE", "BILLIONAIRE", "PRIVILEGE",
                "IRIS", "CE LA VI", "CELAVI", "ATELIER M", "PENTHOUSE", "FIVE PALM",
                "FIVE JVC", "GOOSE ISLAND", "MCGETTIGAN", "IRISH VILLAGE", "FIBBER",
                "LOCK STOCK", "BARASTI", "ZERO GRAVITY", "NIKKI BEACH", "DRIFT",
                "NAMMOS", "SUMMERSALT", "COVE BEACH", "BLA BLA", "JETTY LOUNGE",
                "SHIMMERS", "MERCURY", "TREEHOUSE", "GALAXY BAR", "ZETA", "LUNA SKY",
                "AER", "ONYX", "BO18", "ELECTRIC PAWN", "MISS LILY", "CLAW BBQ",
                "UBK", "BREW HOUSE", "CRAFTY FOX", "REFORM", "DUCK HOOK", "TAP HOUSE",
                "MMI", "AFRICAN + EASTERN", "A+E"
            ),
            vaultKey = "DISCRETIONARY",
            subTag = "#Nightlife"
        ),

        // 6. Discretionary: Grooming & Wellness
        Rule(
            keywords = listOf("GENTS SALON", "1987", "BARBER", "CHAPS", "AKIN", "TONI&GUY", "GROOMING", "SPA"),
            vaultKey = "DISCRETIONARY",
            subTag = "#Grooming"
        ),

        // 7. Discretionary: Dubai Restaurants, Specialty Coffee & Delivery
        Rule(
            keywords = listOf(
                "ZYARA", "ORTO", "FLAT12", "FLAT 12", "GLORIA OSTERIA", "AL GARGHET",
                "NIKU-YA", "NIKUYA", "NNEA", "DOMO", "ZOMATO", "DELIVEROO", "TALABAT",
                "CAREEM FOOD", "SALT", "PICKL", "HIGH JOINT", "SLAB", "LPM", "ZUMA",
                "CIPRIANI", "ROKA", "AMAZONICO", "GAIA", "COYA", "SCALINI", "IL BORRO",
                "CHIC NONNA", "ROBERTO", "VERDE", "BAGATELLE", "NUSR-ET", "CARNA",
                "3FILS", "REIF", "KINOYA", "MOONRISE", "TRESIND", "AVATARA", "BOCA",
                "MASTI", "CARNIVAL", "INDYA", "AL NAFOORAH", "ARABIAN TEA", "LOGMA",
                "SIRAJ", "AL HALLAB", "OPERATION FALAFEL", "ALLO BEIRUT", "ZAROOB",
                "BARBAR", "MANOUSHE", "SHAKE SHACK", "FIVE GUYS", "WAGAMAMA", "PF CHANG",
                "CHEESECAKE FACTORY", "TEXAS ROADHOUSE", "EATALY", "JOE & THE JUICE",
                "PRET", "PAUL", "LE PAIN", "JONES THE GROCER", "COMMON GROUNDS",
                "NIGHTJAR", "STOMPING GROUNDS", "RAW COFFEE", "ARABICA", "BOON",
                "ALCHEMY", "STARBUCKS", "COSTA", "TIM HORTONS", "CAFFE NERO", "CAFE",
                "RESTAURANT", "BISTRO", "KITCHEN", "BAKERY", "PIZZA", "SUSHI", "BURGER", "GRILL"
            ),
            vaultKey = "DISCRETIONARY",
            subTag = "#Dining"
        )
    )

    fun classify(rawMerchant: String, rawSms: String): ClassificationResult {
        val searchSpace = "${rawMerchant.uppercase()} ${rawSms.uppercase()}"
        val cleanedName = rawMerchant
            .replace(Regex("""\s+ARE$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+AE$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+DUBAI$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "Purchase" }

        for (rule in rules) {
            if (rule.keywords.any { keyword -> searchSpace.contains(keyword) }) {
                return ClassificationResult(
                    vaultKey = rule.vaultKey,
                    subTag = rule.subTag,
                    cleanMerchant = cleanedName
                )
            }
        }

        return ClassificationResult(
            vaultKey = "DISCRETIONARY",
            subTag = "#Shopping & Misc",
            cleanMerchant = cleanedName
        )
    }
}
