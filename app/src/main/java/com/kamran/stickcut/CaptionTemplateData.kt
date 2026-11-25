package com.kamran.stickcut

// ---------------------------------------------------------------------------
// The template list. To add a template, add one CaptionTemplate(...) below.
// Every id must be different. Only the built-in fonts are used here.
// Batch 1 (12) came in Step 84. Batch 2 (12) came in Step 87.
// ---------------------------------------------------------------------------
object CaptionTemplates {

    val categories: List<String> = listOf(
        "Trending", "Karaoke", "Minimal", "Bold", "Cinematic", "Documentary", "Podcast",
        "Social", "Kinetic", "Gaming"
    )

    private val batch1: List<CaptionTemplate> = listOf(
        CaptionTemplate(
            id = "tr_white_yellow", name = "White + Yellow", category = "Trending",
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "tr_white_yellow_box", name = "White + Yellow Box", category = "Trending",
            outlineWidth = 0f, background = true, backgroundColor = 0xCC000000L,
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "tr_pop_cyan", name = "Pop Cyan", category = "Trending",
            sizePercent = 6.5f, outlineWidth = 0.14f, highlightColor = 0xFF00E5FFL,
            animation = CaptionAnimation.POP, maxWords = 2,
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "ka_fill_yellow", name = "Karaoke Fill", category = "Karaoke",
            sizePercent = 5.5f, maxWords = 5, maxChars = 32,
            highlight = CaptionHighlightMode.PROGRESSIVE
        ),
        CaptionTemplate(
            id = "ka_pink_box", name = "Karaoke Pink Box", category = "Karaoke",
            sizePercent = 5.5f, outlineWidth = 0f, background = true,
            backgroundColor = 0x99000000L, highlightColor = 0xFFFF4081L,
            maxWords = 5, maxChars = 32, highlight = CaptionHighlightMode.PROGRESSIVE
        ),
        CaptionTemplate(
            id = "mi_clean", name = "Clean White", category = "Minimal",
            font = CaptionFont.SANS, sizePercent = 4.5f, outlineWidth = 0.06f,
            animation = CaptionAnimation.FADE, maxWords = 6, maxChars = 36
        ),
        CaptionTemplate(
            id = "mi_soft_box", name = "Soft Box", category = "Minimal",
            font = CaptionFont.SANS, sizePercent = 5f, outlineWidth = 0f,
            background = true, backgroundColor = 0x66000000L,
            animation = CaptionAnimation.FADE, maxWords = 5, maxChars = 32
        ),
        CaptionTemplate(
            id = "bo_impact", name = "Big Impact", category = "Bold",
            sizePercent = 9f, textColor = 0xFFFFD400L, outlineWidth = 0.2f,
            position = CaptionPosition.MIDDLE, animation = CaptionAnimation.POP,
            maxWords = 2, maxChars = 16, uppercase = true
        ),
        CaptionTemplate(
            id = "bo_red_alert", name = "Red Alert", category = "Bold",
            sizePercent = 7f, outlineWidth = 0f, background = true,
            backgroundColor = 0xFFFF3B30L, animation = CaptionAnimation.SLIDE_UP
        ),
        CaptionTemplate(
            id = "ci_film_serif", name = "Film Serif", category = "Cinematic",
            font = CaptionFont.SERIF, sizePercent = 5f, outlineWidth = 0.08f,
            animation = CaptionAnimation.FADE, maxWords = 6, maxChars = 36
        ),
        CaptionTemplate(
            id = "do_lower_line", name = "Doc Underline", category = "Documentary",
            font = CaptionFont.CONDENSED, sizePercent = 4.8f, outlineWidth = 0f,
            background = true, backgroundColor = 0xAA0A1A44L, highlightColor = 0xFF6BE3A0L,
            animation = CaptionAnimation.SLIDE_UP, maxWords = 5, maxChars = 32,
            highlight = CaptionHighlightMode.UNDERLINE
        ),
        CaptionTemplate(
            id = "po_mid_block", name = "Podcast Block", category = "Podcast",
            sizePercent = 6f, outlineWidth = 0.1f, position = CaptionPosition.MIDDLE,
            maxWords = 4, maxChars = 28, highlight = CaptionHighlightMode.BACKGROUND
        )
    )

    private val batch2: List<CaptionTemplate> = listOf(
        // Trending / Karaoke
        CaptionTemplate(
            id = "tr_orange_pop", name = "Orange Pop", category = "Trending",
            sizePercent = 6.5f, outlineWidth = 0.14f, highlightColor = 0xFFFF9100L,
            animation = CaptionAnimation.POP, maxWords = 3,
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "ka_cyan_progress", name = "Karaoke Cyan", category = "Karaoke",
            sizePercent = 6f, outlineWidth = 0.12f, highlightColor = 0xFF00E5FFL,
            maxWords = 4, maxChars = 28, highlight = CaptionHighlightMode.PROGRESSIVE
        ),
        // Cinematic / Podcast
        CaptionTemplate(
            id = "ci_top_serif", name = "Top Serif", category = "Cinematic",
            font = CaptionFont.SERIF, sizePercent = 4.5f, outlineWidth = 0.08f,
            position = CaptionPosition.TOP, animation = CaptionAnimation.FADE,
            maxWords = 6, maxChars = 36
        ),
        CaptionTemplate(
            id = "po_lower_third", name = "Lower Third", category = "Podcast",
            font = CaptionFont.SANS, sizePercent = 5f, outlineWidth = 0f,
            background = true, backgroundColor = 0xCC050B1FL, highlightColor = 0xFF00E5FFL,
            animation = CaptionAnimation.FADE, maxWords = 5, maxChars = 32,
            highlight = CaptionHighlightMode.UNDERLINE
        ),
        // Social
        CaptionTemplate(
            id = "so_neon_pink", name = "Neon Pink", category = "Social",
            sizePercent = 6.5f, outlineColor = 0xFFAA00FFL, outlineWidth = 0.16f,
            highlightColor = 0xFFFF4081L, animation = CaptionAnimation.POP, maxWords = 2,
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "so_viral_yellow", name = "Viral Yellow", category = "Social",
            sizePercent = 8f, textColor = 0xFFFFD400L, outlineWidth = 0.2f,
            highlightColor = 0xFFFFFFFFL, animation = CaptionAnimation.SLIDE_UP,
            maxWords = 3, uppercase = true, highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "so_pink_bubble", name = "Pink Bubble", category = "Social",
            sizePercent = 6f, outlineWidth = 0f, background = true,
            backgroundColor = 0xFFFF4081L, animation = CaptionAnimation.POP, maxWords = 3
        ),
        // Kinetic
        CaptionTemplate(
            id = "ki_one_word", name = "One Word", category = "Kinetic",
            sizePercent = 10f, outlineWidth = 0.15f, position = CaptionPosition.MIDDLE,
            animation = CaptionAnimation.POP, maxWords = 1, maxChars = 14
        ),
        CaptionTemplate(
            id = "ki_two_slide", name = "Two Slide", category = "Kinetic",
            sizePercent = 8f, outlineWidth = 0.16f, highlightColor = 0xFF00E5FFL,
            position = CaptionPosition.MIDDLE, animation = CaptionAnimation.SLIDE_UP,
            maxWords = 2, maxChars = 18, highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "ki_type_green", name = "Type Green", category = "Kinetic",
            font = CaptionFont.MONO, sizePercent = 5f, textColor = 0xFF6BE3A0L,
            outlineWidth = 0f, background = true, backgroundColor = 0xCC000000L,
            animation = CaptionAnimation.TYPEWRITER, maxWords = 6, maxChars = 36
        ),
        // Gaming
        CaptionTemplate(
            id = "ga_neon_green", name = "Neon Green", category = "Gaming",
            font = CaptionFont.CONDENSED, sizePercent = 7.5f, textColor = 0xFF76FF03L,
            outlineWidth = 0.18f, highlightColor = 0xFFFFFFFFL,
            animation = CaptionAnimation.POP, maxWords = 3, uppercase = true,
            highlight = CaptionHighlightMode.COLOR
        ),
        CaptionTemplate(
            id = "ga_red_box", name = "Red Box", category = "Gaming",
            sizePercent = 6f, outlineWidth = 0f, background = true,
            backgroundColor = 0xCCB71C1CL, animation = CaptionAnimation.SLIDE_UP,
            maxWords = 4, highlight = CaptionHighlightMode.BACKGROUND
        )
    )

    val builtIn: List<CaptionTemplate> = batch1 + batch2
}
