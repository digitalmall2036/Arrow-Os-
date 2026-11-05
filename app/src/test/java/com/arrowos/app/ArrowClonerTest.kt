package com.arrowos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowClonerTest {

    @Test fun taskLinkRoundTrips() {
        val task = ClonerTask("Morning post", listOf("tap open TikTok", "search cats & dogs", "wait 5", "tap like this video"))
        val link = task.toLink()
        assertTrue(link.startsWith("arrowos://run?"))
        assertEquals(task, ClonerTask.fromLink(link))
    }

    @Test fun readsHandTypedLinks() {
        val task = ClonerTask.fromLink("arrowos://run?name=Cats&steps=tap%20open%20youtube;search%20cats;%20;# a note")
        assertEquals("Cats", task?.name)
        assertEquals(listOf("tap open youtube", "search cats"), task?.steps)
    }

    @Test fun readsPlusAsSpaceKeepsEncodedPlusAndGivesADefaultName() {
        val task = ClonerTask.fromLink("arrowos://run?steps=search%20c%2B%2B+tutorial")
        assertEquals("Arrow Cloner task", task?.name)
        assertEquals(listOf("search c++ tutorial"), task?.steps)
    }

    @Test fun ignoresOtherLinksAndEmptyTasks() {
        assertNull(ClonerTask.fromLink("https://example.com/?steps=tap%20home"))
        assertNull(ClonerTask.fromLink("arrowos://run?name=Nothing"))
        assertFalse(ClonerTask.isTaskLink("intent://run"))
    }

    @Test fun builtInPageLinks() {
        assertEquals("skills", ClonerTask.linkAction("arrowos://skills"))
        assertEquals("teach", ClonerTask.linkAction("arrowos://teach"))
        val go = ClonerTask("Cats", listOf("search cats")).toLink() + "&go=1"
        assertEquals("run", ClonerTask.linkAction(go))
        assertTrue(ClonerTask.hasFlag(go, "go"))
        assertFalse(ClonerTask.hasFlag(go, "save"))
        assertEquals(listOf("search cats"), ClonerTask.fromLink(go)?.steps)
        assertNull(ClonerTask.linkAction("https://example.com"))
    }

    @Test fun planRunsTapAndSearchAndWaits() {
        val plan = ClonerPlan.of(ClonerTask("x", listOf("tap open TikTok then search cats", "wait 3 seconds", "tap like this video")))
        assertTrue(plan.problems.isEmpty())
        assertEquals(4, plan.actions.size)
        assertEquals(Command.Open(ArrowApp.TIKTOK), (plan.actions[0] as ClonerAction.Do).command)
        assertEquals(Command.Search("cats", null), (plan.actions[1] as ClonerAction.Do).command)
        assertEquals(3, (plan.actions[2] as ClonerAction.Wait).seconds)
        assertTrue((plan.actions[3] as ClonerAction.Do).command is Command.Tap)
    }

    @Test fun planRefusesStepsThatArentCommandsOrThatChangeArrowOsItself() {
        val plan = ClonerPlan.of(
            ClonerTask(
                "x",
                listOf("open youtube", "tap stop listening", "tap clear history", "tap teach arrow cloner", "tap do morning post", "tap home"),
            ),
        )
        assertEquals(listOf(Command.Home), plan.actions.map { (it as ClonerAction.Do).command })
        assertEquals(5, plan.problems.size)
        assertTrue(plan.problems[0].reason.contains("tap"))
    }

    @Test fun longWaitsAreCapped() {
        val plan = ClonerPlan.of(ClonerTask("x", listOf("wait 500")))
        assertEquals(ClonerTask.MAX_WAIT_SECONDS, (plan.actions.single() as ClonerAction.Wait).seconds)
    }

    @Test fun recorderLearnsOnlyRepeatableCommandsWhileTeaching() {
        val r = ClonerRecorder()
        assertFalse(r.record("tap open youtube")) // not teaching yet
        r.start()
        assertTrue(r.record("Hey Arrow, tap open YouTube"))
        assertTrue(r.record("search Tom and Jerry"))
        assertFalse(r.record("Jeff Bezos"))
        assertFalse(r.record("tap stop listening"))
        assertFalse(r.record("tap save skill as cartoons"))
        assertTrue(r.record("tap play the second video"))
        assertEquals(listOf("tap open youtube", "search tom and jerry", "tap play the second video"), r.stop())
        assertFalse(r.isRecording)
    }

    @Test fun skillsSaveFindAndSurviveARestart() {
        val skills = ClonerSkills()
        skills.save(ClonerTask("Morning post", listOf("tap open tiktok", "tap type good\tmorning\nall and send")))
        skills.save(ClonerTask("Cartoons", listOf("search tom and jerry")))
        skills.save(ClonerTask("cartoons", listOf("search mickey"))) // same name replaces
        assertEquals(2, skills.size)

        val back = ClonerSkills.decode(skills.encode())
        assertEquals(skills.all, back.all)
        assertEquals("Morning post", back.find("morning post")?.name)
        assertEquals("Morning post", back.find("the morning post skill")?.name)
        assertEquals(listOf("search mickey"), back.find("cartoons")?.steps)
        assertNull(back.find("evening"))
        assertTrue(back.remove("CARTOONS"))
        assertEquals(1, back.size)
    }

    @Test fun parserUnderstandsClonerCommands() {
        assertEquals(Command.StartTeaching, CommandParser.parse("tap teach arrow cloner"))
        assertEquals(Command.StopTeaching(null), CommandParser.parse("tap stop teaching"))
        assertEquals(Command.StopTeaching("morning post"), CommandParser.parse("tap save skill as Morning Post"))
        assertEquals(Command.StopTeaching("next week plan"), CommandParser.parse("tap save skill as next week plan"))
        assertEquals(Command.OpenCloner, CommandParser.parse("tap arrow cloner"))
        assertEquals(Command.OpenCloner, CommandParser.parse("tap open arrow cloner"))
        assertEquals(Command.ShowSkills, CommandParser.parse("tap cloner skills"))
        assertEquals(Command.ShowSkills, CommandParser.parse("tap my skills"))
        assertEquals(Command.RunSkill("morning post", "do morning post"), CommandParser.parse("tap do morning post"))
        assertEquals(Command.RunSkill("cartoons", "run skill cartoons"), CommandParser.parse("tap run skill cartoons"))
        assertEquals(Command.Tap("do it", listOf("do it")), CommandParser.parse("tap do it"))
        // Old commands keep working.
        assertEquals(Command.Pause, CommandParser.parse("tap stop"))
        assertTrue(CommandParser.parse("tap save") is Command.Tap)
        assertEquals(Command.NextItem, CommandParser.parse("tap next video"))
    }
}
