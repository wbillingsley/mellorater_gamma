package fivedomains.model

import upickle.default.ReadWriter
import java.util.UUID

case class MellUser(id: UUID, name: String, created: Long) derives ReadWriter
