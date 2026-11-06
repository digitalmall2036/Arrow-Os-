package com.arrowos.app

/**
 * The Auto Designer: the user names their profession or business ("photographer",
 * "I run a restaurant") and Arrow Os suggests the apps and websites people in that
 * line of work use. The user ticks the ones they want and those become the home screen.
 *
 * Everything here is plain Kotlin (no Android), so it is covered by unit tests.
 */
data class BusinessTool(
    /** Stable id saved in preferences. Never change an id once shipped. */
    val id: String,
    val label: String,
    val url: String,
    val colorHex: String,
    /** One short line saying what the tool is for. */
    val description: String,
) {
    /** The letter shown on the home screen tile. */
    val initial: String get() = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}

data class BusinessCategory(
    val name: String,
    /** Words and phrases people use for this line of work. Lowercase. */
    val keywords: List<String>,
    val toolIds: List<String>,
)

/** What the Auto Designer found for what the user typed. */
data class Suggestions(
    /** The categories that matched, best first. Empty when nothing matched. */
    val categories: List<BusinessCategory>,
    /** Tools for the matched categories, best first. */
    val forCategory: List<BusinessTool>,
    /** Tools any business can use, not already in [forCategory]. */
    val general: List<BusinessTool>,
) {
    val matched: Boolean get() = categories.isNotEmpty()
    val all: List<BusinessTool> get() = forCategory + general
}

object AutoDesigner {

    private fun t(id: String, label: String, url: String, color: String, description: String) =
        BusinessTool(id, label, url, color, description)

    val tools: List<BusinessTool> = listOf(
        // Everyday business
        t("gmail", "Gmail", "https://mail.google.com/", "#EA4335", "Email"),
        t("gdrive", "Google Drive", "https://drive.google.com/", "#1FA463", "Store and share files"),
        t("gdocs", "Google Docs", "https://docs.google.com/document/", "#4285F4", "Write documents"),
        t("gsheets", "Google Sheets", "https://docs.google.com/spreadsheets/", "#0F9D58", "Spreadsheets and records"),
        t("gforms", "Google Forms", "https://docs.google.com/forms/", "#7248B9", "Forms, surveys and quizzes"),
        t("gcalendar", "Google Calendar", "https://calendar.google.com/", "#4285F4", "Schedule and appointments"),
        t("gmeet", "Google Meet", "https://meet.google.com/", "#00897B", "Video meetings"),
        t("gmaps", "Google Maps", "https://maps.google.com/", "#34A853", "Maps and directions"),
        t("gbusiness", "Google Business Profile", "https://business.google.com/", "#4285F4", "Show up on Google and Maps"),
        t("gphotos", "Google Photos", "https://photos.google.com/", "#FBBC04", "Back up and share photos"),
        t("gscholar", "Google Scholar", "https://scholar.google.com/", "#4285F4", "Research papers"),
        t("gclassroom", "Google Classroom", "https://classroom.google.com/", "#0F9D58", "Run classes online"),
        t("gads", "Google Ads", "https://ads.google.com/", "#FBBC04", "Advertise on Google"),
        t("ganalytics", "Google Analytics", "https://analytics.google.com/", "#E37400", "Website visitors and stats"),
        t("whatsapp", "WhatsApp Web", "https://web.whatsapp.com/", "#25D366", "Chat with customers"),
        t("whatsapp_business", "WhatsApp Business", "https://business.whatsapp.com/", "#128C7E", "Catalog and customer chat"),
        t("canva", "Canva", "https://www.canva.com/", "#00C4CC", "Design posters, flyers and posts"),
        t("linkedin", "LinkedIn", "https://www.linkedin.com/", "#0A66C2", "Professional network"),
        t("zoom", "Zoom", "https://zoom.us/", "#2D8CFF", "Video calls"),
        t("calendly", "Calendly", "https://calendly.com/", "#006BFF", "Let clients book you"),
        t("trello", "Trello", "https://trello.com/", "#0079BF", "Plan tasks and projects"),
        t("notion", "Notion", "https://www.notion.so/", "#111111", "Notes and planning"),
        t("claude", "Claude", "https://claude.ai/", "#D97757", "AI assistant for any task"),
        t("docusign", "DocuSign", "https://www.docusign.com/", "#4C00FF", "Sign documents online"),
        t("paypal", "PayPal", "https://www.paypal.com/", "#003087", "Send and receive payments"),
        t("stripe", "Stripe", "https://dashboard.stripe.com/", "#635BFF", "Take card payments"),
        t("office", "Microsoft 365", "https://www.office.com/", "#D83B01", "Word, Excel and Outlook"),

        // Arrow Os's own apps
        t("youtube", "YouTube", ArrowApp.YOUTUBE.homeUrl, ArrowApp.YOUTUBE.colorHex, "Videos"),
        t("facebook", "Facebook", ArrowApp.FACEBOOK.homeUrl, ArrowApp.FACEBOOK.colorHex, "Social network"),
        t("instagram", "Instagram", ArrowApp.INSTAGRAM.homeUrl, ArrowApp.INSTAGRAM.colorHex, "Photos and reels"),
        t("tiktok", "TikTok", ArrowApp.TIKTOK.homeUrl, ArrowApp.TIKTOK.colorHex, "Short videos"),
        t("nine_apps", "9apps", ArrowApp.NINE_APPS.homeUrl, ArrowApp.NINE_APPS.colorHex, "Download apps"),

        // Design and photo
        t("figma", "Figma", "https://www.figma.com/", "#A259FF", "Design apps and websites"),
        t("adobe_express", "Adobe Express", "https://new.express.adobe.com/", "#EB1000", "Quick graphics and videos"),
        t("photopea", "Photopea", "https://www.photopea.com/", "#18A497", "Free photo editor"),
        t("pixlr", "Pixlr", "https://pixlr.com/", "#3B82F6", "Online photo editor"),
        t("lightroom", "Lightroom", "https://lightroom.adobe.com/", "#31A8FF", "Edit and organise photos"),
        t("removebg", "remove.bg", "https://www.remove.bg/", "#5E5CE6", "Remove photo backgrounds"),
        t("behance", "Behance", "https://www.behance.net/", "#1769FF", "Show your portfolio"),
        t("dribbble", "Dribbble", "https://dribbble.com/", "#EA4C89", "Design inspiration and clients"),
        t("pinterest", "Pinterest", "https://www.pinterest.com/", "#E60023", "Ideas and inspiration"),
        t("unsplash", "Unsplash", "https://unsplash.com/", "#111111", "Free photos"),
        t("px500", "500px", "https://500px.com/", "#0099E5", "Photography community"),

        // Video, creators and music
        t("yt_studio", "YouTube Studio", "https://studio.youtube.com/", "#FF0033", "Manage your channel"),
        t("capcut", "CapCut", "https://www.capcut.com/", "#111111", "Edit videos"),
        t("clipchamp", "Clipchamp", "https://app.clipchamp.com/", "#7B61FF", "Edit videos online"),
        t("spotify_artists", "Spotify for Artists", "https://artists.spotify.com/", "#1DB954", "Your music on Spotify"),
        t("soundcloud", "SoundCloud", "https://soundcloud.com/", "#FF5500", "Share your tracks"),
        t("bandlab", "BandLab", "https://www.bandlab.com/", "#F12C18", "Make music online"),
        t("audiomack", "Audiomack", "https://audiomack.com/", "#FFA200", "Stream and share music"),
        t("distrokid", "DistroKid", "https://distrokid.com/", "#0B3D91", "Release music to stores"),

        // Selling
        t("shopify", "Shopify", "https://www.shopify.com/", "#96BF48", "Build an online store"),
        t("fb_marketplace", "Facebook Marketplace", "https://www.facebook.com/marketplace/", "#1877F2", "Buy and sell locally"),
        t("meta_business", "Meta Business Suite", "https://business.facebook.com/", "#0668E1", "Manage Facebook and Instagram pages"),
        t("amazon_seller", "Amazon Seller", "https://sellercentral.amazon.com/", "#FF9900", "Sell on Amazon"),
        t("ebay", "eBay", "https://www.ebay.com/", "#E53238", "Sell online"),
        t("etsy", "Etsy", "https://www.etsy.com/", "#F1641E", "Sell handmade goods"),
        t("square", "Square", "https://squareup.com/", "#111111", "Point of sale and payments"),

        // Food and hospitality
        t("ubereats_merchant", "Uber Eats Manager", "https://merchants.ubereats.com/", "#06C167", "Take delivery orders"),
        t("opentable", "OpenTable", "https://restaurant.opentable.com/", "#DA3743", "Table bookings"),
        t("toast", "Toast", "https://pos.toasttab.com/", "#FF4C00", "Restaurant point of sale"),
        t("booking_partner", "Booking.com Partner", "https://admin.booking.com/", "#003580", "List rooms on Booking.com"),
        t("airbnb_host", "Airbnb Hosting", "https://www.airbnb.com/hosting", "#FF5A5F", "Host guests"),
        t("tripadvisor", "Tripadvisor", "https://www.tripadvisor.com/", "#34E0A1", "Reviews and listings"),
        t("eventbrite", "Eventbrite", "https://www.eventbrite.com/", "#F05537", "Sell event tickets"),

        // Property and building
        t("zillow", "Zillow", "https://www.zillow.com/", "#006AFF", "Homes for sale and rent"),
        t("realtor", "Realtor.com", "https://www.realtor.com/", "#D92228", "Property listings"),
        t("sketchup", "SketchUp", "https://app.sketchup.com/", "#005F9E", "3D building models"),
        t("floorplanner", "Floorplanner", "https://floorplanner.com/", "#F26B38", "Draw floor plans"),
        t("autodesk", "Autodesk", "https://www.autodesk.com/", "#0696D7", "AutoCAD and design software"),
        t("procore", "Procore", "https://www.procore.com/", "#F47E42", "Run construction projects"),

        // Health
        t("pubmed", "PubMed", "https://pubmed.ncbi.nlm.nih.gov/", "#20558A", "Medical research"),
        t("medscape", "Medscape", "https://www.medscape.com/", "#00599C", "Medical news and reference"),
        t("drugs", "Drugs.com", "https://www.drugs.com/", "#0B5394", "Drug information"),
        t("who", "WHO", "https://www.who.int/", "#009ADE", "World Health Organization guidance"),
        t("doximity", "Doximity", "https://www.doximity.com/", "#1A73E8", "Network for clinicians"),
        t("myfitnesspal", "MyFitnessPal", "https://www.myfitnesspal.com/", "#0072CE", "Nutrition tracking"),
        t("strava", "Strava", "https://www.strava.com/", "#FC4C02", "Track runs and rides"),
        t("trainerize", "Trainerize", "https://www.trainerize.com/", "#00B1E1", "Coach clients online"),

        // Education
        t("khan", "Khan Academy", "https://www.khanacademy.org/", "#14BF96", "Free lessons"),
        t("kahoot", "Kahoot!", "https://kahoot.com/", "#46178F", "Classroom quizzes"),
        t("quizlet", "Quizlet", "https://quizlet.com/", "#4255FF", "Flashcards and study sets"),

        // Law
        t("justia", "Justia", "https://www.justia.com/", "#1F4E79", "Free laws and cases"),
        t("findlaw", "FindLaw", "https://www.findlaw.com/", "#C8102E", "Legal information"),

        // Money
        t("quickbooks", "QuickBooks", "https://quickbooks.intuit.com/", "#2CA01C", "Accounting and invoices"),
        t("xero", "Xero", "https://www.xero.com/", "#13B5EA", "Accounting software"),
        t("wave", "Wave", "https://www.waveapps.com/", "#1C6CF2", "Free invoicing and accounting"),

        // Tech
        t("github", "GitHub", "https://github.com/", "#24292F", "Code hosting"),
        t("stackoverflow", "Stack Overflow", "https://stackoverflow.com/", "#F48024", "Programming answers"),
        t("replit", "Replit", "https://replit.com/", "#F26207", "Code in the browser"),
        t("codepen", "CodePen", "https://codepen.io/", "#111111", "Try web code"),
        t("mdn", "MDN Web Docs", "https://developer.mozilla.org/", "#1B1B1B", "Web developer reference"),
        t("vercel", "Vercel", "https://vercel.com/", "#111111", "Put websites online"),

        // Marketing and writing
        t("mailchimp", "Mailchimp", "https://mailchimp.com/", "#FFE01B", "Email newsletters"),
        t("hubspot", "HubSpot", "https://app.hubspot.com/", "#FF7A59", "Customers and sales (CRM)"),
        t("buffer", "Buffer", "https://buffer.com/", "#2C4BFF", "Schedule social media posts"),
        t("grammarly", "Grammarly", "https://app.grammarly.com/", "#15C39A", "Fix grammar and spelling"),
        t("medium", "Medium", "https://medium.com/", "#111111", "Publish articles"),
        t("substack", "Substack", "https://substack.com/", "#FF6719", "Paid newsletters"),
        t("wordpress", "WordPress", "https://wordpress.com/", "#21759B", "Build a website or blog"),
        t("hemingway", "Hemingway Editor", "https://hemingwayapp.com/", "#C0392B", "Make writing clear"),

        // Beauty
        t("fresha", "Fresha", "https://www.fresha.com/", "#111111", "Salon bookings"),
        t("booksy", "Booksy", "https://booksy.com/", "#00A3AD", "Barber and beauty bookings"),

        // Transport
        t("uber_driver", "Uber Driver", "https://www.uber.com/drive/", "#111111", "Drive with Uber"),
        t("bolt_driver", "Bolt Driver", "https://bolt.eu/en/driver/", "#34D186", "Drive with Bolt"),
        t("waze", "Waze", "https://www.waze.com/live-map/", "#33CCFF", "Live traffic"),
        t("dhl", "DHL", "https://www.dhl.com/", "#FFCC00", "Ship and track parcels"),
        t("fedex", "FedEx", "https://www.fedex.com/", "#4D148C", "Ship and track parcels"),

        // Farming
        t("fao", "FAO", "https://www.fao.org/", "#116AAB", "UN food and farming guidance"),
        t("plantix", "Plantix", "https://plantix.net/", "#2E9E48", "Diagnose crop problems"),
        t("windy", "Windy", "https://www.windy.com/", "#9D0300", "Weather forecasts"),

        // Work
        t("indeed", "Indeed", "https://www.indeed.com/", "#2164F3", "Post and find jobs"),
        t("glassdoor", "Glassdoor", "https://www.glassdoor.com/", "#0CAA41", "Company reviews and salaries"),
        t("upwork", "Upwork", "https://www.upwork.com/", "#14A800", "Find freelance work"),
        t("fiverr", "Fiverr", "https://www.fiverr.com/", "#1DBF73", "Sell your services"),
    )

    private val byId: Map<String, BusinessTool> = tools.associateBy { it.id }

    fun tool(id: String): BusinessTool? = byId[id]

    val categories: List<BusinessCategory> = listOf(
        BusinessCategory(
            "Graphic design",
            listOf("graphic design", "graphic designer", "design", "designer", "logo", "branding", "illustrator", "illustration",
                "creative", "artist", "ui", "ux", "printing", "print shop"),
            listOf("canva", "figma", "adobe_express", "photopea", "removebg", "behance", "dribbble", "pinterest", "unsplash"),
        ),
        BusinessCategory(
            "Photography",
            listOf("photography", "photographer", "photo", "photos", "camera", "studio", "wedding photographer", "photo studio"),
            listOf("lightroom", "photopea", "pixlr", "removebg", "instagram", "px500", "unsplash", "pinterest", "gphotos"),
        ),
        BusinessCategory(
            "Content creation",
            listOf("content creator", "creator", "youtuber", "influencer", "video", "videographer", "vlogger", "vlog",
                "film", "filmmaker", "video editor", "tiktoker", "streamer", "blogger", "media"),
            listOf("youtube", "yt_studio", "tiktok", "instagram", "capcut", "clipchamp", "canva", "facebook", "meta_business"),
        ),
        BusinessCategory(
            "Music",
            listOf("music", "musician", "singer", "producer", "music producer", "dj", "rapper", "band", "beat", "beats", "artist",
                "songwriter", "studio"),
            listOf("spotify_artists", "soundcloud", "bandlab", "audiomack", "distrokid", "youtube", "tiktok", "instagram"),
        ),
        BusinessCategory(
            "Shop and online selling",
            listOf("shop", "store", "retail", "retailer", "ecommerce", "e commerce", "online store", "online shop", "seller",
                "selling", "sell", "boutique", "trader", "trading", "merchant", "supermarket", "fashion", "clothing", "clothes",
                "tailor", "shoes", "electronics", "wholesale", "dropshipping"),
            listOf("shopify", "whatsapp_business", "instagram", "fb_marketplace", "meta_business", "square", "amazon_seller",
                "ebay", "etsy", "paypal"),
        ),
        BusinessCategory(
            "Restaurant and food",
            listOf("restaurant", "food", "cafe", "coffee", "coffee shop", "bakery", "baker", "catering", "caterer", "chef", "cook",
                "bar", "fast food", "kitchen", "pizza", "takeaway", "eatery"),
            listOf("gbusiness", "ubereats_merchant", "opentable", "toast", "square", "whatsapp_business", "instagram", "canva"),
        ),
        BusinessCategory(
            "Real estate",
            listOf("real estate", "realtor", "property", "properties", "estate agent", "landlord", "broker", "housing", "rentals",
                "rental", "letting"),
            listOf("zillow", "realtor", "fb_marketplace", "gmaps", "docusign", "whatsapp_business", "canva", "linkedin"),
        ),
        BusinessCategory(
            "Construction and architecture",
            listOf("construction", "builder", "building", "contractor", "architect", "architecture", "civil engineer",
                "civil engineering", "plumber", "plumbing", "electrician", "carpenter", "interior design", "interior designer",
                "mason", "painter", "surveyor"),
            listOf("sketchup", "floorplanner", "autodesk", "procore", "gdrive", "gsheets", "gmaps", "pinterest"),
        ),
        BusinessCategory(
            "Health and medicine",
            listOf("doctor", "nurse", "nursing", "health", "healthcare", "medical", "medicine", "clinic", "hospital", "pharmacy",
                "pharmacist", "dentist", "dental", "physician", "surgeon", "midwife", "therapist", "lab"),
            listOf("pubmed", "medscape", "drugs", "who", "doximity", "gcalendar", "zoom", "gscholar"),
        ),
        BusinessCategory(
            "Fitness and wellness",
            listOf("gym", "fitness", "personal trainer", "trainer", "yoga", "pilates", "coach", "nutritionist", "dietitian",
                "wellness", "sports"),
            listOf("trainerize", "myfitnesspal", "strava", "youtube", "instagram", "calendly", "canva", "whatsapp_business"),
        ),
        BusinessCategory(
            "Education and teaching",
            listOf("teacher", "teaching", "school", "education", "tutor", "tutoring", "lecturer", "professor", "student",
                "trainer", "coach", "academy", "college", "university", "classes", "course"),
            listOf("gclassroom", "khan", "kahoot", "quizlet", "gforms", "gmeet", "zoom", "canva", "youtube"),
        ),
        BusinessCategory(
            "Law",
            listOf("lawyer", "legal", "law", "attorney", "advocate", "paralegal", "solicitor", "notary", "law firm", "barrister"),
            listOf("justia", "findlaw", "gscholar", "docusign", "gdocs", "calendly", "gmail", "claude"),
        ),
        BusinessCategory(
            "Accounting and finance",
            listOf("accountant", "accounting", "finance", "financial", "bookkeeping", "bookkeeper", "tax", "taxes", "auditor",
                "audit", "banking", "bank", "insurance", "investment", "invoices"),
            listOf("quickbooks", "xero", "wave", "gsheets", "office", "paypal", "stripe", "docusign"),
        ),
        BusinessCategory(
            "Software and tech",
            listOf("developer", "programmer", "programming", "software", "software engineer", "coding", "coder", "code",
                "web developer", "web design", "ict", "it support", "tech", "technology", "app developer", "data", "data scientist"),
            listOf("github", "stackoverflow", "claude", "replit", "codepen", "mdn", "vercel", "figma", "notion"),
        ),
        BusinessCategory(
            "Marketing and social media",
            listOf("marketing", "marketer", "social media", "social media manager", "advertising", "ads", "digital marketing",
                "seo", "pr", "public relations", "brand", "promotion", "agency"),
            listOf("meta_business", "canva", "mailchimp", "hubspot", "buffer", "gads", "ganalytics", "linkedin", "tiktok", "instagram"),
        ),
        BusinessCategory(
            "Writing and journalism",
            listOf("writer", "writing", "author", "journalist", "journalism", "copywriter", "copywriting", "editor", "poet",
                "content writer", "news", "publisher", "blogger"),
            listOf("gdocs", "grammarly", "hemingway", "medium", "substack", "wordpress", "claude", "notion"),
        ),
        BusinessCategory(
            "Beauty and salon",
            listOf("salon", "barber", "barbershop", "hair", "hairdresser", "beauty", "makeup", "make up", "nails", "spa",
                "stylist", "cosmetics", "skincare", "lashes"),
            listOf("fresha", "booksy", "instagram", "tiktok", "whatsapp_business", "gbusiness", "canva", "calendly"),
        ),
        BusinessCategory(
            "Transport and delivery",
            listOf("driver", "taxi", "transport", "transportation", "logistics", "delivery", "courier", "trucking", "truck",
                "shipping", "uber", "bolt", "rider", "boda", "dispatch"),
            listOf("uber_driver", "bolt_driver", "gmaps", "waze", "dhl", "fedex", "whatsapp", "gsheets"),
        ),
        BusinessCategory(
            "Farming and agriculture",
            listOf("farmer", "farming", "farm", "agriculture", "agricultural", "agribusiness", "livestock", "poultry", "crops",
                "crop", "dairy", "fishing", "fish farming", "cattle", "garden", "gardener"),
            listOf("plantix", "fao", "windy", "fb_marketplace", "whatsapp_business", "youtube", "gmaps", "gsheets"),
        ),
        BusinessCategory(
            "Events",
            listOf("events", "event", "event planner", "wedding planner", "wedding", "party", "decor", "decoration", "mc",
                "planner", "conference"),
            listOf("eventbrite", "canva", "pinterest", "gcalendar", "calendly", "gsheets", "whatsapp_business", "instagram"),
        ),
        BusinessCategory(
            "Hotels and travel",
            listOf("hotel", "travel", "travel agent", "tour", "tours", "tourism", "tour guide", "airbnb", "hospitality", "lodge",
                "guest house", "guesthouse", "hostel", "safari", "bnb"),
            listOf("booking_partner", "airbnb_host", "tripadvisor", "gbusiness", "gmaps", "whatsapp_business", "instagram", "canva"),
        ),
        BusinessCategory(
            "Hiring and HR",
            listOf("hr", "human resources", "recruiter", "recruitment", "recruiting", "hiring", "talent", "staffing", "payroll"),
            listOf("linkedin", "indeed", "glassdoor", "gforms", "calendly", "zoom", "gsheets", "docusign"),
        ),
        BusinessCategory(
            "Freelance and office work",
            listOf("freelancer", "freelance", "consultant", "consulting", "virtual assistant", "assistant", "admin",
                "administrator", "secretary", "office", "manager", "entrepreneur", "startup", "ceo"),
            listOf("upwork", "fiverr", "linkedin", "calendly", "zoom", "notion", "trello", "gmail", "claude"),
        ),
    )

    /** Shown under every result: tools any business can use. */
    val generalToolIds: List<String> = listOf(
        "gmail", "whatsapp_business", "gbusiness", "canva", "gdrive", "gcalendar", "linkedin", "claude",
    )

    /** A few categories offered as one-tap examples. */
    val popular: List<String> = listOf(
        "Shop", "Restaurant", "Photographer", "Designer", "Content creator", "Teacher", "Doctor", "Lawyer",
        "Accountant", "Developer", "Salon", "Farmer", "Driver", "Real estate",
    )

    /**
     * The tool picker's search bar: every catalog tool whose name or purpose matches [query],
     * best first, plus the tools of a matching business category. Empty query: nothing.
     */
    fun searchTools(query: String, limit: Int = 20): List<BusinessTool> {
        val words = normalizeWords(query)
        if (words.isEmpty()) return emptyList()
        val stems = words.map(::stem)
        val phrase = words.joinToString(" ")
        val scored = tools.map { tool ->
            val label = normalizeWords(tool.label)
            val about = normalizeWords(tool.description)
            var score = 0
            if (label.joinToString(" ") == phrase) score += 100
            if (label.joinToString(" ").startsWith(phrase)) score += 40
            for (w in stems) {
                if (label.any { sameWord(stem(it), w) || it.startsWith(w) }) score += 10
                if (about.any { sameWord(stem(it), w) }) score += 3
            }
            tool to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        val byCategory = suggest(query).forCategory
        return (scored + byCategory).distinctBy { it.id }.take(limit)
    }

    fun suggest(input: String): Suggestions {
        val words = normalizeWords(input)
        val scored = if (words.isEmpty()) emptyList() else categories
            .map { it to score(words, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val top = scored.firstOrNull()?.second ?: 0
        // The best category, plus a runner-up that matched almost as well
        // ("photographer and designer" gets both).
        val picked = scored.filter { it.second * 2 >= top }.take(2).map { it.first }

        val forCategory = picked.flatMap { it.toolIds }.distinct().mapNotNull { byId[it] }
        val shown = forCategory.map { it.id }.toSet()
        val general = generalToolIds.filter { it !in shown }.mapNotNull { byId[it] }
        return Suggestions(picked, forCategory, general)
    }

    /**
     * How well [words] fit [category]. A keyword counts when all its words appear in the input
     * (allowing plurals and endings like "photographers" for "photographer"). Longer phrases
     * count more, so "software engineer" beats a lone "engineer".
     */
    private fun score(words: List<String>, category: BusinessCategory): Int {
        val stems = words.map(::stem)
        var total = 0
        for (keyword in category.keywords) {
            val kw = normalizeWords(keyword)
            if (kw.isNotEmpty() && kw.all { k -> stems.any { sameWord(stem(k), it) } }) total += kw.sumOf { it.length }
        }
        return total
    }

    /** Equal stems, or one is the other plus a short ending ("stor" and "store"). */
    private fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 4 && long.startsWith(short) && long.length - short.length <= 2
    }

    private val fillers = setOf(
        "i", "im", "am", "a", "an", "the", "my", "is", "in", "of", "and", "or", "for", "to", "we", "our", "run", "own", "work",
        "do", "as", "at", "with", "small", "big", "local", "professional", "category", "s",
    )

    internal fun normalizeWords(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in fillers }

    /** A rough word stem so "photographers", "photographer" and "photography" don't need separate entries. */
    internal fun stem(word: String): String {
        var w = word
        if (w.length > 4 && w.endsWith("ies")) w = w.dropLast(3) + "y"
        else if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.dropLast(1)
        // Twice, so "deliveries", "delivery" and "deliver" all end up as "deliv".
        repeat(2) {
            val suffix = listOf("ing", "ers", "er", "ist", "y").firstOrNull { w.length > it.length + 3 && w.endsWith(it) }
            if (suffix != null) w = w.dropLast(suffix.length)
        }
        return w
    }
}

/** The user's choices from the Auto Designer, saved across launches. */
data class HomeSetup(val category: String, val toolIds: List<String>) {

    val tools: List<BusinessTool> get() = toolIds.mapNotNull { CustomSites.toolFor(it) }

    /** The same setup with [ids] added at the end (ones already there are kept once). */
    fun plus(ids: List<String>): HomeSetup = copy(toolIds = (toolIds + ids.filter { CustomSites.toolFor(it) != null }).distinct())

    fun minus(id: String): HomeSetup = copy(toolIds = toolIds - id)

    /** The ticked tool the user named ("tap open Canva"), or null. */
    fun findTool(spoken: String): BusinessTool? {
        val said = AutoDesigner.normalizeWords(spoken).joinToString(" ")
        if (said.isEmpty()) return null
        return tools.firstOrNull { AutoDesigner.normalizeWords(it.label).joinToString(" ") == said }
    }

    fun encode(): String = category.replace("\n", " ") + "\n" + toolIds.joinToString(",")

    companion object {
        fun decode(raw: String?): HomeSetup? {
            if (raw.isNullOrBlank()) return null
            val lines = raw.split("\n", limit = 2)
            val ids = lines.getOrNull(1).orEmpty().split(",").map { it.trim() }.filter { CustomSites.toolFor(it) != null }
            if (ids.isEmpty()) return null
            return HomeSetup(lines[0].trim(), ids)
        }
    }
}

/**
 * Websites the user adds from the tool picker's search bar that are not in the catalog,
 * for example "notion.so" or "app.netlify.com". They are saved in [HomeSetup] as ids like
 * "site:Netlify|https%3A%2F%2Fapp.netlify.com%2F" (no commas, so the setup can still be split).
 */
object CustomSites {

    private const val PREFIX = "site:"
    private val colors = listOf("#7C4DFF", "#00897B", "#F4511E", "#3949AB", "#C0CA33", "#8E24AA", "#00ACC1", "#6D4C41")

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = java.net.URLDecoder.decode(s, "UTF-8")

    /** The id for a website called [label] at [address] ("netlify.com" or a full https address). */
    fun idFor(label: String, address: String): String? {
        val url = normalizeUrl(address) ?: return null
        val name = label.trim().ifBlank { hostOf(url) }.take(40)
        return PREFIX + enc(name) + "|" + enc(url)
    }

    /** A catalog tool, or the website saved in a custom id, or null. */
    fun toolFor(id: String): BusinessTool? {
        AutoDesigner.tool(id)?.let { return it }
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split("|")
        if (parts.size != 2) return null
        return try {
            val label = dec(parts[0])
            val url = normalizeUrl(dec(parts[1])) ?: return null
            BusinessTool(id, label, url, colors[(label.lowercase().hashCode() and 0x7fffffff) % colors.size], hostOf(url))
        } catch (e: Exception) {
            null
        }
    }

    /** "netlify.com" -> "https://netlify.com/"; anything that isn't an address -> null. */
    fun normalizeUrl(address: String): String? {
        val a = address.trim()
        val withScheme = when {
            a.startsWith("https://") || a.startsWith("http://") -> a
            CommandParser.looksLikeAddress(a.lowercase()) -> "https://$a"
            else -> return null
        }
        val host = hostOf(withScheme)
        if (host.isEmpty() || !host.contains('.') || host.contains(' ')) return null
        return if (withScheme.removePrefix("https://").removePrefix("http://").contains('/')) withScheme else "$withScheme/"
    }

    fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').removePrefix("www.")
}
