package fivedomains

import scala.collection.mutable
import com.wbillingsley.veautiful.PushVariable

import org.scalajs.dom.window
import window.localStorage 
import window.console

import model.{given, *}

// Pickling library, for stringifying data to store in localStorage
import upickle.default.*
// Automatic writers for case classes
import upickle.default.{ReadWriter => RW, macroRW}
import typings.std.stdStrings.a

import fivedomains.model.Confidence
import java.util.UUID
import com.wbillingsley.veautiful.logging.Logger


// Animal, Confidence, Answer and Assessment now derive ReadWriter directly in the common module,
// so their givens are picked up from their companion objects without redeclaring here.

case class DataBlob(
    acceptedSensitiveTopics:Boolean,
    animalMap:mutable.Map[AnimalId, Animal] = mutable.Map.empty[AnimalId, Animal],
    assessments:mutable.Buffer[Assessment] = mutable.Buffer.empty[Assessment]
)  
 
/** 
 * While we're prototyping, the data store is just held in memory. This will change 
 * - first to saving it in the browser's temporary storage
 * - then to some kind of server solution
 */
object DataStore {

    val acceptedSensitiveTopics = PushVariable(
        Option(localStorage.getItem("acceptedSensitiveTopics")).map(read[Boolean](_)).getOrElse(false)
    ) { value => Router.routeTo(AppRoute.Front) }

    /** Triggered by the accept button on the first use notice */
    def acceptSensitiveTopics(save:Boolean) = 
        if save then localStorage.setItem("acceptedSensitiveTopics", write(true))
        acceptedSensitiveTopics.value = true

    def clearAcceptSensitiveTopics() = 
        localStorage.setItem("acceptedSensitiveTopics", write(false))
        acceptedSensitiveTopics.value = false



    val animalMap:mutable.Map[AnimalId, Animal] =
        val stored = Option(localStorage.getItem("animalMap"))
        stored match {
            case Some(json) =>
                val parsed = read[Map[AnimalId, Animal]](json) 
                parsed.to(mutable.Map)
                
            case None => mutable.Map.empty[AnimalId, Animal] 
        }
    

    def animal(a:AnimalId) = animalMap(a)

    def addAnimal(a:Animal):Animal =
        animalMap(a.id) = a
        localStorage.setItem("animalMap", write(animalMap))
        a

    def nextAnimalId = UUID.randomUUID()

    private val _assessments:mutable.Buffer[Assessment] = 
        val stored = Option(localStorage.getItem("assessments"))
        stored match {
            case Some(json) =>
                try {
                    val parsed = read[Seq[Assessment]](json) 
                    parsed.to(mutable.Buffer)
                } catch {
                    case x:Throwable => 
                        console.error(x)
                        mutable.Buffer.empty
                }
                //mutable.Buffer.empty[Assessment]
            case None => 
                mutable.Buffer.empty[Assessment]
        }
        

    /** Saves a new assessment to memory and JSON */
    def addAssessment(a:Assessment):Unit = 
        _assessments.append(a)
        localStorage.setItem("assessments", write(assessments))

    def addAssessment(animal:AnimalId, situation:Situation, time:Double, answers:Seq[(AnswerValue, Confidence, Option[String])]):Unit = 
        _assessments.append(Assessment(animal=animal, situation=situation, time=time, 
          (for ((ans, conf, note), i) <- answers.zipWithIndex yield i -> Answer(i, ans, conf, note)).toMap
        ))
        localStorage.setItem("assessments", write(assessments))

    def assessments = _assessments.toSeq

    def surveysFor(a:Animal) = assessments.toSeq.filter(_.animal == a.id)

    private val _aiFeedback:mutable.Map[(AnimalId, Double), AiFeedback] =
        val stored = Option(localStorage.getItem("aiFeedback"))
        stored match {
            case Some(json) =>
                try
                    read[Seq[AiFeedback]](json).map(f => (f.animal, f.assessmentTime) -> f).to(mutable.Map)
                catch
                    case x:Throwable =>
                        console.error(x)
                        mutable.Map.empty[(AnimalId, Double), AiFeedback]
            case None => mutable.Map.empty[(AnimalId, Double), AiFeedback]
        }

    def aiFeedbackFor(animal:AnimalId, time:Double):Option[AiFeedback] = _aiFeedback.get((animal, time))

    /** Caches AI-generated feedback locally, keyed by (animal, assessment time) since Assessment
      * has no id of its own. See fivedomains.Ai for where this is generated and pushed to the
      * server.
      */
    def saveAiFeedback(f:AiFeedback):Unit =
        _aiFeedback((f.animal, f.assessmentTime)) = f
        localStorage.setItem("aiFeedback", write(_aiFeedback.values.toSeq))

    def animals = animalMap.values.toSeq.sortBy(_.id)

    def testAnimals = animalMap.values.filter(_.testData == true).toSeq.sortBy(_.id)

    def realAminals = animalMap.values.filter(_.testData == false).toSeq.sortBy(_.id)

    def hasRealData = animalMap.values.exists(_.testData == false)

    def hasTestData = animalMap.values.exists(_.testData == true)

    /** Delete all stored data */
    def clearAll() =
        _assessments.clear()
        animalMap.clear()
        _aiFeedback.clear()
        localStorage.setItem("assessments", write(assessments))
        localStorage.setItem("animalMap", write(animalMap))
        localStorage.setItem("aiFeedback", write(_aiFeedback.values.toSeq))

    def clearDemoAnimals() =
        // Only keep assessments (and their AI feedback) from non-test animals
        val keepAssessments = _assessments.filter((as) => animalMap.get(as.animal).exists(!_.testData))
        val keepAiFeedback = _aiFeedback.filter((_, f) => animalMap.get(f.animal).exists(!_.testData))
        _assessments.clear()
        _assessments.appendAll(keepAssessments)
        localStorage.setItem("assessments", write(assessments))

        _aiFeedback.clear()
        _aiFeedback.addAll(keepAiFeedback)
        localStorage.setItem("aiFeedback", write(_aiFeedback.values.toSeq))

        val keepAnimals = animalMap.filter((id, a) => !a.testData)
        animalMap.clear()
        animalMap.addAll(keepAnimals)
        localStorage.setItem("animalMap", write(animalMap))

}