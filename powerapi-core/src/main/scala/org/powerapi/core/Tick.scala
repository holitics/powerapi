/*
 * This software is licensed under the GNU Affero General Public License, quoted below.
 *
 * This file is a part of PowerAPI.
 *
 * Copyright (C) 2011-2016 Inria, University of Lille 1.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI.
 *
 * If not, please consult http://www.gnu.org/licenses/agpl-3.0.html.
 */
package org.powerapi.core

import java.util.UUID

import akka.actor.ActorRef

/**
  * Base trait for Tick messages.
  *
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
trait Tick extends Message {
  /**
    * Subject used for routing the message.
    */
  def topic: String

  /**
    * Origin timestamp.
    */
  def timestamp: Long
}

/**
  * Tick channel.
  *
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
object TickChannel extends Channel {

  type M = Tick

  /**
    * Used to subscribe/unsubscribe to Tick on the right topic.
    */
  def subscribeTick(muid: UUID): MessageBus => ActorRef => Unit = {
    subscribe(tickTopic(muid))
  }

  def unsubscribeTick(muid: UUID): MessageBus => ActorRef => Unit = {
    unsubscribe(tickTopic(muid))
  }

  /**
    * Used to format the topic used to interact with the MonitorChild actors.
    */
  def tickTopic(muid: UUID): String = {
    s"tick:$muid"
  }

  /**
    * Used to publish a Tick message built externally on the right topic.
    */
  def publishTick(tick: Tick): MessageBus => Unit = {
    publish(tick)
  }
}
