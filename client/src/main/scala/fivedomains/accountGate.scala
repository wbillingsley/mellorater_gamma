package fivedomains

import com.wbillingsley.veautiful.*
import html.{Styling, DHtmlComponent, <, ^, EventMethods}
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

enum GateMode:
    case Choice
    case Registering
    case ShowRecovery
    case Linking

/** Shown instead of the app when there's no logged-in user: offers to create a new account or
  * link an existing one via its recovery phrase. See Auth.scala for the underlying calls.
  */
class AccountGate() extends DHtmlComponent {

    val mode = stateVariable(GateMode.Choice)
    val nameInput = stateVariable("")
    val phraseInput = stateVariable("")
    val pendingRegistration = stateVariable(Option.empty[Auth.RegisterResponse])
    val error = stateVariable(Option.empty[String])
    val busy = stateVariable(false)

    def submitRegister(): Unit =
        error.value = None
        busy.value = true
        Auth.register(nameInput.value.trim).onComplete {
            case Success(Right(response)) =>
                busy.value = false
                pendingRegistration.value = Some(response)
                mode.value = GateMode.ShowRecovery
            case Success(Left(msg)) =>
                busy.value = false
                error.value = Some(msg)
            case Failure(_) =>
                busy.value = false
                error.value = Some("Couldn't reach the server. Please try again.")
        }

    def submitLink(): Unit =
        error.value = None
        busy.value = true
        Auth.link(phraseInput.value.trim).onComplete {
            case Success(Right(())) => () // Auth.state flips to LoggedIn, which re-renders the app past this gate
            case Success(Left(msg)) =>
                busy.value = false
                error.value = Some(msg)
            case Failure(_) =>
                busy.value = false
                error.value = Some("Couldn't reach the server. Please try again.")
        }

    def errorBlock = error.value match
        case Some(msg) => <.p(^.style := s"color: $dangerFg;", msg)
        case None => <.span()

    def render = <.div(^.cls := (top),
        <.div(^.cls := (notice),
            mode.value match
                case GateMode.Choice => renderChoice
                case GateMode.Registering => renderRegistering
                case GateMode.ShowRecovery => renderShowRecovery
                case GateMode.Linking => renderLinking
        )
    )

    def renderChoice = <.div(
        <.h3("Welcome"),
        <.p("This app keeps your animals' records under a simple account -- no email or password needed."),
        <.p(<.button(^.cls := (button, primary), "Create a new account", ^.onClick --> { mode.value = GateMode.Registering })),
        <.p(<.button(^.cls := (button), "I already have an account", ^.onClick --> { mode.value = GateMode.Linking }))
    )

    def renderRegistering = <.div(
        <.h3("Create a new account"),
        <.p("What should we call you?"),
        <.input(^.prop("value") := nameInput.value, ^.attr.placeholder := "Your name",
            ^.on("input") ==> { e => for n <- e.inputValue do nameInput.value = n }
        ),
        errorBlock,
        <.p(
            <.button(^.cls := (button, primary),
                (if busy.value || nameInput.value.trim.isEmpty then Seq(^.attr("disabled") := "disabled") else Seq()),
                "Create account", ^.onClick --> submitRegister()
            ),
            <.button(^.cls := (button), "Back", ^.onClick --> { error.value = None; mode.value = GateMode.Choice })
        )
    )

    def renderShowRecovery = <.div(
        <.h3("Save your recovery phrase"),
        <.p("This is the only way to get back into your account from another device. Write it down or save it somewhere safe -- it won't be shown again."),
        <.p(^.style := "font-family: monospace; font-size: 1.3em; text-align: center; background: white; padding: 0.5em; border-radius: 0.25em;",
            pendingRegistration.value.map(_.recoveryPhrase).getOrElse("")
        ),
        <.p(
            <.button(^.cls := (button, primary), "I've saved it, continue", ^.onClick --> {
                for r <- pendingRegistration.value do Auth.confirmSession(r.token, r.user)
            })
        )
    )

    def renderLinking = <.div(
        <.h3("Link this device"),
        <.p("Paste the recovery phrase you were given when you created your account."),
        <.input(^.prop("value") := phraseInput.value, ^.attr.placeholder := "XXXX-XXXX-XXXX-XXXX",
            ^.on("input") ==> { e => for p <- e.inputValue do phraseInput.value = p }
        ),
        errorBlock,
        <.p(
            <.button(^.cls := (button, primary),
                (if busy.value || phraseInput.value.trim.isEmpty then Seq(^.attr("disabled") := "disabled") else Seq()),
                "Link account", ^.onClick --> submitLink()
            ),
            <.button(^.cls := (button), "Back", ^.onClick --> { error.value = None; mode.value = GateMode.Choice })
        )
    )
}
