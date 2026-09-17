package fivedomains.animals

import fivedomains.{given, *}
import model.*
import com.wbillingsley.veautiful.*
import html.*
import assessments.*
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global


/**
 * A short summary message indicating how many assessments we've got on file, etc
 */
def assessmentQuantumStats(a:Animal, surveys:Seq[Assessment]) =
    // TODO - check if many of the surveys have been with low confidence
    <.div(^.cls := nakedParaMargins,
        <.p(
            f"Overall confidence across the surveys was ${surveys.overallConfidence * 100}%.0f%%."
        ),
        <.p(
            for s <- surveys.lastOption.toSeq yield <.div(
                s"The most revent survey was on ${new scalajs.js.Date(s.time).toLocaleDateString}. ",
                f"Overall confidence in this survey was ${s.overallConfidence * 100}%.0f%%.",
            )

        )

    )


enum SummarySort(val text:String):
    case DomainOrder extends SummarySort("Domain order")
    case AreasOfConcern extends SummarySort("Areas of concern")

enum Trend:
    case Up, Down, Steady, Unknown

/** Compares a domain's score in `current` against the same animal's most recent earlier
  * assessment (from `allSurveysAsc`, sorted oldest first), so the summary can show whether
  * things are trending up or down.
  */
def domainTrend(allSurveysAsc:Seq[Assessment], current:Assessment, d:Domain):Trend =
    val previous = allSurveysAsc.filter(_.time < current.time).lastOption
    (previous.flatMap(_.categoryScore(d)), current.categoryScore(d)) match
        case (Some(prev), Some(now)) =>
            if now - prev > 5 then Trend.Up
            else if prev - now > 5 then Trend.Down
            else Trend.Steady
        case _ => Trend.Unknown

def trendIcon(t:Trend) =
    t match
        case Trend.Up => <.span(^.cls := "material-symbols-outlined", ^.style := s"color: $fgGood; vertical-align: middle;", ^.attr.title := "Improved since the previous assessment", "trending_up")
        case Trend.Down => <.span(^.cls := "material-symbols-outlined", ^.style := s"color: $fgVeryPoor; vertical-align: middle;", ^.attr.title := "Declined since the previous assessment", "trending_down")
        case Trend.Steady => <.span(^.cls := "material-symbols-outlined", ^.style := "color: gray; vertical-align: middle;", ^.attr.title := "About the same as the previous assessment", "trending_flat")
        case Trend.Unknown => <.span()

/** A summary of one assessment, grouped by domain, with a trend indicator against the animal's
  * previous assessment. `sort` chooses whether every domain/question is shown (in domain order)
  * or only the ones that are a current concern.
  */
def pastRedFlags(animal:Animal, allSurveys:Seq[Assessment], selected:Option[Assessment], sort:SummarySort):DHtmlModifier =
    selected match
        case None => None
        case Some(assess) =>
            val ascSurveys = allSurveys.sortBy(_.time)

            val domains = sort match
                case SummarySort.DomainOrder => Domain.values.toSeq
                case SummarySort.AreasOfConcern => assess.domainsContainingConcern.toSeq

            // Pair each domain with the questions to show under it, dropping domains left with none
            // (e.g. "areas of concern" mode, for a domain with nothing currently concerning).
            val domainsWithQuestions:Seq[(Domain, Seq[Question])] =
                domains.map { d =>
                    val questions = sort match
                        case SummarySort.DomainOrder => domainQuestions(d)
                        case SummarySort.AreasOfConcern => domainQuestions(d).filter(q => assess.answers.get(q.num).exists(_.value.asDouble <= Rating.Poor.value))
                    d -> questions
                }.filter((_, qs) => qs.nonEmpty)

            <.div(^.cls := nakedParaMargins,

                if domainsWithQuestions.isEmpty then <.p("No areas of concern in this assessment.") else None,

                for (d, questions) <- domainsWithQuestions yield
                    <.div(
                        <.h4(
                            <.div(^.style := "float: left; margin-right: 8px;", unboxedDomainLogo(d, assess.categoryScore(d))),
                            d.title, " ", trendIcon(domainTrend(ascSurveys, assess, d))
                        ),

                        for
                            q <- questions
                            a <- assess.answers.get(q.num)
                        yield
                            <.div(^.style := "margin: 0 0 1em 1.5em;",
                                <.h5(
                                    <.div(^.style := "float: left; margin-right: 10px; margin-top: 2px;", boxedScoreFaceHtml(Some(a.value.asDouble))),
                                    q.headline(animal),
                                    if a.confidence.low then <.span(^.cls := "material-symbols-outlined", ^.style := "vertical-align: middle;", "question_mark") else None
                                ),
                                for n <- a.note yield <.p(^.style := "font-style: italic;", n),
                                <.div(feedback(animal, assess, QuestionIdentifier.fromOrdinal(a.q)))
                            )
                    )
            )

/** Invokes the AI feedback call and shows the result, structured under each welfare domain.
  * Shows cached feedback (from DataStore, backfilled from the server if needed) with a
  * "Regenerate" option if there is any; otherwise offers to generate it.
  */
case class AiFeedbackPanel(animal:Animal, assess:Assessment) extends DHtmlComponent {

    val busy = stateVariable(false)
    val error = stateVariable(Option.empty[String])

    def generate():Unit =
        error.value = None
        busy.value = true
        Ai.generateFeedback(animal, assess).onComplete {
            case Success(Right(_)) => busy.value = false
            case Success(Left(msg)) =>
                busy.value = false
                error.value = Some(msg)
            case Failure(_) =>
                busy.value = false
                error.value = Some("Couldn't reach the AI. Please try again.")
        }

    def backendLabel = Ai.backend.value match
        case AiBackend.Server => "this app's server"
        case AiBackend.PuterJs => "puter.js, in your browser"

    def errorBlock = error.value match
        case Some(msg) => <.p(^.style := s"color: $dangerFg;", msg)
        case None => <.span()

    override def render =
        Ai.ensureHydrated(animal.id)

        DataStore.aiFeedbackFor(animal.id, assess.time) match
            case Some(fb) =>
                <.div(^.cls := nakedParaMargins,
                    <.h3("AI feedback"),
                    <.p(fb.overall),

                    for
                        d <- Domain.values.toSeq
                        text <- fb.perDomain.get(d.toString)
                    yield
                        <.div(
                            <.h4(unboxedDomainLogo(d, assess.categoryScore(d)), " ", d.title),
                            <.p(text)
                        ),

                    errorBlock,
                    <.p(
                        <.button(^.cls := (fivedomains.button, noticeButton), ^.prop.disabled := busy.value,
                            if busy.value then "Regenerating..." else "Regenerate AI feedback",
                            ^.onClick --> generate()
                        )
                    )
                )
            case None =>
                <.div(^.cls := (notice),
                    <.p(s"Get AI-generated feedback on this assessment, written up under each welfare domain, using $backendLabel. (Change the AI backend in Settings.)"),
                    errorBlock,
                    <.p(
                        <.button(^.cls := (fivedomains.button, primary), ^.prop.disabled := busy.value,
                            if busy.value then "Asking the AI..." else "Get AI feedback",
                            ^.onClick --> generate()
                        )
                    )
                )

}

def animalDetailsPage(aId:AnimalId) =
    val a = DataStore.animal(aId)
    val surveys = DataStore.surveysFor(a)

    <.div(
        leftBlockHeader(
            Router.path(AppRoute.Front),
            "Animal details",
            <.label(^.cls := (animalName), a.name)
        ),

        SurveySelectWidget(a, surveys)

    )


case class SurveySelectWidget(animal:Animal, surveys:Seq[Assessment]) extends DHtmlComponent {

    val mode = stateVariable(SummarySort.DomainOrder)

    val max = surveys.length
    val number = stateVariable(max)

    def subset = surveys.reverse.drop(max - number.value)

    override def render =
        val selected = subset.headOption

        <.div(

        <.div(^.cls := (alignCentreStyle, stickyTop, bgWhite),

            scoringRose(subset),

            selected match {
                    case Some(assess) =>
                        val d = new scalajs.js.Date(assess.time)
                        <.h4(d.toLocaleDateString(), " ", d.toLocaleTimeString())
                    case None => <.span()
            },

            <.p(

                "Time machine: ",
                <.button(^.cls := "button material-symbols-outlined", "arrow_left", ^.prop.disabled := number.value <= 1, ^.onClick --> {number.value = number.value - 1}),
                <.input(
                    ^.attr("type") := "range", ^.attr("min") := 1, ^.attr("max") := max,
                    ^.prop("value") := number.value, ^.on.input ==> { e => for v <- e.inputValue do number.value = v.toInt }
                ),
                <.button(^.cls := "button material-symbols-outlined", "arrow_right", ^.prop.disabled := number.value >= max, ^.onClick --> {number.value = number.value + 1}),
            ),


            <.p(
                "Show ",
                <.select(^.style := s"margin-left: 0.25em; ",
                    ^.on.change ==> { (e) =>
                        val n = e.target.asInstanceOf[scalajs.js.Dynamic].value.asInstanceOf[String]
                        mode.value = SummarySort.fromOrdinal(n.toInt)
                    },
                    for s <- SummarySort.values yield
                        <.option(
                            ^.prop.value := s.ordinal, s.text,
                            if mode.value == s then ^.prop.selected := "selected" else None
                        )
                )
            ),


        ),

        pastRedFlags(animal, surveys, selected, mode.value),

        for assess <- selected.toSeq yield AiFeedbackPanel(animal, assess)
        )

}
