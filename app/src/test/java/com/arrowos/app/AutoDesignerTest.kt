package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDesignerTest {

    private fun topCategory(input: String) = AutoDesigner.suggest(input).categories.firstOrNull()?.name

    @Test fun findsTheCategoryForCommonProfessions() {
        assertEquals("Photography", topCategory("Photographer"))
        assertEquals("Photography", topCategory("wedding photographers"))
        assertEquals("Restaurant and food", topCategory("I run a restaurant"))
        assertEquals("Restaurant and food", topCategory("Bakery"))
        assertEquals("Law", topCategory("lawyer"))
        assertEquals("Accounting and finance", topCategory("Accountant"))
        assertEquals("Education and teaching", topCategory("primary school teacher"))
        assertEquals("Health and medicine", topCategory("Doctor"))
        assertEquals("Beauty and salon", topCategory("hair salon"))
        assertEquals("Farming and agriculture", topCategory("poultry farming"))
        assertEquals("Transport and delivery", topCategory("delivery driver"))
        assertEquals("Shop and online selling", topCategory("clothes shop"))
        assertEquals("Shop and online selling", topCategory("online stores"))
        assertEquals("Real estate", topCategory("Real Estate Agent"))
        assertEquals("Graphic design", topCategory("graphic designer"))
        assertEquals("Content creation", topCategory("YouTuber"))
    }

    @Test fun longerPhrasesWin() {
        assertEquals("Software and tech", topCategory("software engineer"))
        assertEquals("Construction and architecture", topCategory("civil engineer"))
    }

    @Test fun twoProfessionsGiveBothSetsOfTools() {
        val s = AutoDesigner.suggest("photographer and graphic designer")
        val names = s.categories.map { it.name }.toSet()
        assertEquals(setOf("Photography", "Graphic design"), names)
        val ids = s.forCategory.map { it.id }
        assertTrue("lightroom" in ids)
        assertTrue("figma" in ids)
        assertEquals("no tool listed twice", ids.size, ids.toSet().size)
    }

    @Test fun suggestionsListCategoryToolsFirstThenGeneralOnesWithoutRepeats() {
        val s = AutoDesigner.suggest("restaurant")
        assertTrue(s.matched)
        assertEquals("gbusiness", s.forCategory.first().id)
        val all = s.all.map { it.id }
        assertEquals(all.size, all.toSet().size)
        assertFalse("gbusiness" in s.general.map { it.id })
        assertTrue("gmail" in s.general.map { it.id })
    }

    @Test fun unknownBusinessStillGetsGeneralTools() {
        val s = AutoDesigner.suggest("zzqx blorp")
        assertFalse(s.matched)
        assertTrue(s.forCategory.isEmpty())
        assertEquals(AutoDesigner.generalToolIds, s.general.map { it.id })
    }

    @Test fun emptyInputMatchesNothing() {
        assertFalse(AutoDesigner.suggest("   ").matched)
        assertFalse(AutoDesigner.suggest("I am a").matched)
    }

    @Test fun catalogIsConsistent() {
        val ids = AutoDesigner.tools.map { it.id }
        assertEquals("tool ids are unique", ids.size, ids.toSet().size)
        AutoDesigner.tools.forEach { tool ->
            assertTrue("${tool.id} has a web address", tool.url.startsWith("https://"))
            assertNotNull("${tool.id} has a valid address", java.net.URI(tool.url).host)
            assertTrue("${tool.id} has a color", Regex("#[0-9A-Fa-f]{6}").matches(tool.colorHex))
            assertTrue(tool.label.isNotBlank() && tool.description.isNotBlank())
        }
        (AutoDesigner.categories.flatMap { it.toolIds } + AutoDesigner.generalToolIds).forEach {
            assertNotNull("unknown tool id $it", AutoDesigner.tool(it))
        }
        AutoDesigner.popular.forEach { assertTrue("popular \"$it\" matches", AutoDesigner.suggest(it).matched) }
    }

    @Test fun theFiveArrowAppsOpenAsThemselves() {
        assertEquals(ArrowApp.YOUTUBE, ArrowApp.fromUrl(AutoDesigner.tool("youtube")!!.url))
        assertEquals(ArrowApp.TIKTOK, ArrowApp.fromUrl(AutoDesigner.tool("tiktok")!!.url))
    }

    @Test fun homeSetupSurvivesSavingAndLoading() {
        val setup = HomeSetup("Photographer", listOf("lightroom", "canva", "instagram"))
        assertEquals(setup, HomeSetup.decode(setup.encode()))
        assertEquals(listOf("Lightroom", "Canva", "Instagram"), setup.tools.map { it.label })
    }

    @Test fun homeSetupDropsToolsThatNoLongerExist() {
        assertEquals(listOf("canva"), HomeSetup.decode("Shop\ncanva,gone_tool")?.toolIds)
        assertNull(HomeSetup.decode("Shop\ngone_tool"))
        assertNull(HomeSetup.decode(null))
        assertNull(HomeSetup.decode(""))
    }

    @Test fun findsATickedToolByName() {
        val setup = HomeSetup("Designer", listOf("canva", "gdrive", "removebg"))
        assertEquals("canva", setup.findTool("Canva")?.id)
        assertEquals("gdrive", setup.findTool("google drive")?.id)
        assertEquals("removebg", setup.findTool("remove bg")?.id)
        assertNull(setup.findTool("figma"))
    }

    @Test fun stemsMatchCommonEndings() {
        assertEquals(AutoDesigner.stem("photographer"), AutoDesigner.stem("photography"))
        assertEquals(AutoDesigner.stem("photographer"), AutoDesigner.stem("photographers"))
        assertEquals(AutoDesigner.stem("teacher"), AutoDesigner.stem("teaching"))
        assertEquals(AutoDesigner.stem("delivery"), AutoDesigner.stem("deliveries"))
    }
}
