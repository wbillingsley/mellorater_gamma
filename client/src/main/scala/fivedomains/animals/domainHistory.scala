package fivedomains.animals

import fivedomains.{given, *}
import model.*
import com.wbillingsley.veautiful.*
import html.*
import assessments.*

/** A domain score below this (Poor or Very Poor) counts as a concern */
val concernThreshold = 40.0

def isConcern(score:Option[Double]) = score.exists(_ < concernThreshold)

def formatDate(time:Double) = new scalajs.js.Date(time).toLocaleDateString()

/** "A", "A and B", "A, B and C" */
def listDomains(ds:Seq[Domain]):String = ds.map(_.title) match
    case Seq() => ""
    case Seq(a) => a
    case init :+ last => init.mkString(", ") + " and " + last

/** A few sentences describing the overall pattern of an animal's wellbeing over its surveys.
  * `history` is sorted oldest first and ends with the survey being viewed. Only the last few
  * surveys are considered, so old problems that have long since resolved don't dominate.
  */
def wellbeingSummary(animal:Animal, history:Seq[Assessment]):Seq[String] =
    val recent = history.takeRight(5)
    val latest = recent.last
    val previous = recent.dropRight(1)
    val domains = Domain.scoredDomains

    def concernsIn(a:Assessment) = domains.filter(d => isConcern(a.categoryScore(d)))
    def isAre(ds:Seq[Domain]) = if ds.size == 1 then "is" else "are"
    def hasHave(ds:Seq[Domain]) = if ds.size == 1 then "has" else "have"

    val now = concernsIn(latest)

    val opener = latest.overallScore.map(s => s"Overall wellbeing in this survey is ${scoreText(s).toLowerCase}.").toSeq

    val pattern:Seq[String] = previous.lastOption match
        case None =>
            if now.isEmpty then Seq(s"Only one survey has been recorded for ${animal.name} so far, and no domain is currently a concern.")
            else Seq(s"Only one survey has been recorded for ${animal.name} so far. It suggests problems with ${listDomains(now)}.")

        case Some(prev) =>
            val before = concernsIn(prev)
            val ongoing = now.filter(before.contains)
            val emerging = now.filterNot(before.contains)

            val recovering = domains.filter { d =>
                latest.categoryScore(d).nonEmpty && !isConcern(latest.categoryScore(d)) && previous.exists(a => isConcern(a.categoryScore(d)))
            }
            val dipping = domains.filter { d =>
                !isConcern(latest.categoryScore(d)) && !recovering.contains(d) &&
                (for p <- prev.categoryScore(d); n <- latest.categoryScore(d) yield p - n >= 20).getOrElse(false)
            }

            val problems =
                if ongoing.nonEmpty && emerging.nonEmpty then
                    Seq(s"Problems appear to be spreading: ${listDomains(ongoing)} ${hasHave(ongoing)} been a concern since the previous survey, and now ${listDomains(emerging)} ${isAre(emerging)} too.")
                else
                    (if ongoing.nonEmpty then Seq(s"There are ongoing problems with ${listDomains(ongoing)}, which ${isAre(ongoing)} also a concern in the previous survey.") else Nil) ++
                    (if emerging.nonEmpty then Seq(s"${listDomains(emerging)} ${hasHave(emerging)} recently become a concern.") else Nil)

            val recoveries = if recovering.nonEmpty then Seq(s"${listDomains(recovering)} ${isAre(recovering)} recovering from a recent dip.") else Nil
            val dips = if dipping.nonEmpty then Seq(s"${listDomains(dipping)} ${hasHave(dipping)} dropped noticeably since the previous survey, though not yet to a level of concern.") else Nil

            val all = problems ++ recoveries ++ dips
            if all.nonEmpty then all
            else if recent.forall(a => domains.forall(d => a.categoryScore(d).forall(_ >= 60))) then
                Seq(s"${animal.name}'s wellbeing has been consistently good across all domains over the last ${recent.size} surveys.")
            else
                Seq("No domain is currently a concern, although some scores are only neutral.")

    opener ++ pattern :+ "Scroll down to request more specific, AI-based feedback on this survey."


enum HistorySort:
    case DomainOrder, Latest, Change, Notes

val historyTable = Styling("width: 100%; border-collapse: collapse; font-size: 15px;").modifiedBy(
    " th" -> "text-align: left; padding: 4px; border-bottom: 2px solid #ccc; cursor: pointer; user-select: none; white-space: nowrap;",
    " td" -> "padding: 4px; border-bottom: 1px solid #eee; vertical-align: middle;",
    " tr.domain-row" -> "cursor: pointer;",
    " tr.domain-row:hover" -> s"background: $cream;",
    " .material-symbols-outlined" -> "vertical-align: middle;",
).register()

/** A sortable table with a row per domain, showing its scores over the animal's recent surveys and
  * how many notes have been recorded against it. Clicking a row expands it to show the history of
  * notes. `history` is sorted oldest first and ends with the survey being viewed.
  */
case class DomainHistoryTable(animal:Animal, history:Seq[Assessment]) extends DHtmlComponent {

    val sort = stateVariable(HistorySort.DomainOrder)
    val descending = stateVariable(false)
    val expanded = stateVariable(Set.empty[Domain])

    // How many past surveys get a column in the mini history
    val columns = history.takeRight(6)
    val latest = history.last
    val previous = history.dropRight(1).lastOption

    def change(d:Domain) =
        for p <- previous.flatMap(_.categoryScore(d)); n <- latest.categoryScore(d) yield n - p

    /** Notes recorded against a domain's questions, newest first */
    def notes(d:Domain):Seq[(Assessment, Question, Answer, String)] =
        for
            a <- history.reverse
            q <- domainQuestions(d)
            ans <- a.answers.get(q.num).toSeq
            n <- ans.note.toSeq if n.trim.nonEmpty
        yield (a, q, ans, n)

    // Ascending order puts the most worrying rows first for every column except domain order
    def sorted =
        val s = sort.value match
            case HistorySort.DomainOrder => Domain.values.toSeq
            case HistorySort.Latest => Domain.values.toSeq.sortBy(d => latest.categoryScore(d).getOrElse(Double.MaxValue))
            case HistorySort.Change => Domain.values.toSeq.sortBy(d => change(d).getOrElse(0.0))
            case HistorySort.Notes => Domain.values.toSeq.sortBy(d => -notes(d).size)
        if descending.value then s.reverse else s

    def header(s:HistorySort, label:String) =
        <.th(
            ^.onClick --> {
                if sort.value == s then descending.value = !descending.value
                else
                    sort.value = s
                    descending.value = false
            },
            label,
            if sort.value == s then <.span(^.cls := "material-symbols-outlined", if descending.value then "arrow_drop_up" else "arrow_drop_down") else None
        )

    def toggle(d:Domain) =
        expanded.value = if expanded.value.contains(d) then expanded.value - d else expanded.value + d

    def miniHistory(d:Domain) =
        <.div(^.style := "display: flex; gap: 2px;",
            for a <- columns yield
                val score = a.categoryScore(d)
                <.div(
                    ^.style := s"width: 12px; height: 18px; border-radius: 2px; background: ${scoreColor(score)};",
                    ^.attr.title := s"${formatDate(a.time)}: ${score.map(scoreText).getOrElse("Incomplete")}"
                )
        )

    def notesHistory(d:Domain) =
        val ns = notes(d)
        <.tr(<.td(^.attr("colspan") := 5, ^.style := "padding: 0.5em 1em 1em 2em;",
            if ns.isEmpty then <.p(^.style := "color: gray; margin: 0;", "No notes recorded for this domain.")
            else
                for (a, q, ans, n) <- ns yield
                    <.div(^.style := "margin-bottom: 0.75em;",
                        <.div(<.strong(formatDate(a.time)), s" — ${q.headline(animal)} (${ans.value.labelText})"),
                        <.div(^.style := "font-style: italic;", n)
                    )
        ))

    override def render =
        <.table(^.cls := historyTable,
            <.thead(<.tr(
                header(HistorySort.DomainOrder, "Domain"),
                <.th(^.style := "cursor: default;", "Recent"),
                header(HistorySort.Latest, "Now"),
                header(HistorySort.Change, "Trend"),
                header(HistorySort.Notes, "Notes"),
            )),
            <.tbody(
                sorted.flatMap { d =>
                    val noteCount = notes(d).size
                    val open = expanded.value.contains(d)
                    Seq(
                        <.tr(^.cls := "domain-row", ^.onClick --> toggle(d),
                            <.td(
                                <.span(^.cls := "material-symbols-outlined", if open then "expand_less" else "expand_more"),
                                <.span(^.style := s"color: ${scoreColor(latest.categoryScore(d))}; margin: 0 4px;", domainLogo(d)),
                                d.title
                            ),
                            <.td(miniHistory(d)),
                            <.td(boxedScoreFaceHtml(latest.categoryScore(d))),
                            <.td(trendIcon(domainTrend(history, latest, d))),
                            <.td(
                                if noteCount > 0 then
                                    <.span(^.attr.title := s"$noteCount note(s) recorded", <.span(^.cls := "material-symbols-outlined", "sticky_note_2"), s" $noteCount")
                                else <.span(^.style := "color: lightgray;", "—")
                            )
                        )
                    ) ++ (if open then Seq(notesHistory(d)) else Nil)
                }
            )
        )

}

/** The summary of the animal's wellbeing up to the selected survey: some text describing the
  * overall pattern, then the per-domain history table.
  */
def assessmentHistory(animal:Animal, allSurveys:Seq[Assessment], selected:Option[Assessment]):DHtmlModifier =
    for assess <- selected yield
        val history = allSurveys.sortBy(_.time).filter(_.time <= assess.time)
        <.div(^.cls := nakedParaMargins,
            for p <- wellbeingSummary(animal, history) yield <.p(p),
            DomainHistoryTable(animal, history)
        )
