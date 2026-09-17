package fivedomains

import com.wbillingsley.veautiful.*
import html.{VHtmlContent, Styling, DHtmlComponent, Animator}
import fivedomains.testdata.addPickles
import fivedomains.DataStore.hasTestData
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

object SettingsScreen extends DHtmlComponent {
    import html.{<, ^}

    def render = <.div(
        "Settings"
    )

}

/** A widget for resetting the first time notice */
case class ResetFirstTimeNotice() extends DHtmlComponent {

    val enabled = stateVariable(false)

    def clearFlag() = 
        DataStore.clearAcceptSensitiveTopics() 

    override def render = {
        import html.{<, ^}
        <.div(
            <.p(
                """|The first use notice shows a sensitive topics warning and other disclaimers. This resets the flag about whether it should appear
                   |""".stripMargin

            ),

            <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value}),
            <.label("Tick to unlock"),
            <.button(^.cls := (button, noticeButton), ^.prop.disabled := !enabled.value, "Reset first use notice", ^.onClick --> clearFlag())
        )
    }

}


/** A widget for resetting the first time notice */
case class ResetEventLog() extends DHtmlComponent {

    val enabled = stateVariable(false)

    def clearLogs() = 
        Analytics.resetLogs()
        enabled.value = false

    override def render = {
        import html.{<, ^}
        <.div(
            <.p(
                """|The app keeps a local event log in your browser for analytics. (This is not sent to any server anywhere.) This can be cleared.
                   |After clearing, I recommend refreshing the page, in order to record a new start-of-session event.
                   |""".stripMargin

            ),

            <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value}),
            <.label("Tick to unlock"),
            <.button(^.cls := (button, noticeButton), ^.prop.disabled := !enabled.value, "Reset event log", ^.onClick --> clearLogs())
        )
    }

}

/** A widget for resetting all locally saved data ready for the next user */
case class ResetData() extends DHtmlComponent {

    val enabled = stateVariable(false)

    def reset() = 
        DataStore.clearAll()
        enabled.value = false

    override def render = {
        import html.{<, ^}
        <.div(
            <.p(
                """|The app currently stores its data only in the browser's local storage. 
                   |If the data is reset, it cannot be recovered.
                   |""".stripMargin

            ),

            <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value}),
            <.label("Tick to unlock"),
            <.button(^.cls := (button, dangerButton), ^.prop.disabled := !enabled.value, "Reset all data", ^.onClick --> reset())
        )
    }

}


/** A widget for revealing (by regenerating) the account's recovery phrase, in case it was lost
  * or never saved. Regenerating invalidates whatever recovery phrase the account had before --
  * device tokens already issued keep working.
  */
case class RecoveryPhrase() extends DHtmlComponent {

    val enabled = stateVariable(false)
    val busy = stateVariable(false)
    val phrase = stateVariable(Option.empty[String])
    val error = stateVariable(Option.empty[String])

    def regenerate() =
        error.value = None
        busy.value = true
        Auth.regenerateRecoveryPhrase().onComplete {
            case Success(Right(p)) =>
                busy.value = false
                enabled.value = false
                phrase.value = Some(p)
            case Success(Left(msg)) =>
                busy.value = false
                error.value = Some(msg)
            case Failure(_) =>
                busy.value = false
                error.value = Some("Couldn't reach the server. Please try again.")
        }

    override def render = {
        import html.{<, ^}
        <.div(
            <.p(
                """|Your recovery phrase is the only way to link a new device to your account. If you've lost it, or never
                   |saved it, you can get a new one here -- but doing so invalidates your old recovery phrase (any device
                   |you're already using stays logged in).
                   |""".stripMargin
            ),

            phrase.value match
                case Some(p) =>
                    <.p(^.style := "font-family: monospace; font-size: 1.3em; text-align: center; background: white; padding: 0.5em; border-radius: 0.25em;", p)
                case None =>
                    <.span()
            ,

            error.value match
                case Some(msg) => <.p(^.style := s"color: $dangerFg;", msg)
                case None => <.span()
            ,

            <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value }),
            <.label("Tick to unlock"),
            <.button(^.cls := (button, noticeButton), ^.prop.disabled := (!enabled.value || busy.value), "Get a new recovery phrase", ^.onClick --> regenerate())
        )
    }

}


/** A widget for choosing which backend generates AI advice for assessments -- this app's own
  * server (which calls Groq), or puter.js running in the browser (using the visitor's own Puter
  * account). See Ai.scala.
  */
case class AiBackendToggle() extends DHtmlComponent {

    override def render = {
        import html.{<, ^}
        <.div(
            <.p(
                """|Assessments can include AI-generated advice. Choose whether that's generated by this app's
                   |server, or by puter.js running in your browser using your own Puter account.
                   |""".stripMargin
            ),
            <.label(
                <.input(^.attr("type") := "radio", ^.attr("name") := "aiBackend",
                    ^.prop.checked := (Ai.backend.value == AiBackend.Server),
                    ^.onChange --> { Ai.backend.value = AiBackend.Server }
                ),
                " This app's server"
            ),
            <.label(^.style := "margin-left: 1.5em;",
                <.input(^.attr("type") := "radio", ^.attr("name") := "aiBackend",
                    ^.prop.checked := (Ai.backend.value == AiBackend.PuterJs),
                    ^.onChange --> { Ai.backend.value = AiBackend.PuterJs }
                ),
                " puter.js (in your browser)"
            )
        )
    }

}


/** A widget for adding or removing demo animals */
case class DemoData() extends DHtmlComponent {

    def clear() = 
        DataStore.clearDemoAnimals()
        enabled.value = false

    def add() = 
        addPickles()
        enabled.value = false

    val enabled = stateVariable(false)


    override def render = {
        import html.{<, ^}

            if hasTestData then 
                <.div(
                        <.p(
                        """|To remove the demo animals, click the button below. You can re-add the demo animals again afterwards, but any assessments
                           |you've made of them will be lost.
                        |""".stripMargin
                        ),

                        <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value}),
                        <.label("Tick to unlock"),
                        <.button(^.cls := (button, noticeButton), ^.prop.disabled := !enabled.value, "Clear demo animals", ^.onClick --> clear())
                )

            else
                <.div(
                        <.p(
                        """|The app can add some demo animals for you. It marks them as not being real animals, so they're easy to remove later.
                        |""".stripMargin
                        ),

                        <.input(^.attr("type") := "checkbox", ^.prop.checked := enabled.value, ^.onChange --> { enabled.value = !enabled.value}),
                        <.label("Tick to unlock"),
                        <.button(^.cls := (button, noticeButton), ^.prop.disabled := !enabled.value, "Add demo anials", ^.onClick --> add())
                )
    }

}

def settingsPage = 
    import html.* 
    <.div(
        leftBlockHeader(
            Router.path(AppRoute.Front),
            "Settings",
            <.p()
        ),

        <.div(^.style := "margin: 1em;",


        <.h2("Account"),
        RecoveryPhrase(),

        <.h2("AI advice"),
        AiBackendToggle(),

        <.h2("First use"),
        ResetFirstTimeNotice(),

        <.h2("Logs"),
        ResetEventLog(),

        <.h2("Data"),
        ResetData(),
        DemoData()

        
        )

    )
