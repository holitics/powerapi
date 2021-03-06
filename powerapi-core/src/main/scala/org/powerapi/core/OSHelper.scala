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

import java.io.{File, IOException}

import scala.collection.JavaConverters._
import scala.io.Source
import scala.sys.process.stringSeqToProcess
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import org.hyperic.sigar.ptql.ProcessFinder
import org.hyperic.sigar.{SigarException, SigarProxy}
import org.powerapi.core.FileHelper.using
import org.powerapi.core.target.{Application, Container, Process, Target}

/**
  * This is not a monitoring target. It's an internal wrapper for the Thread IDentifier.
  *
  * @param tid thread identifier
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
case class Thread(tid: Int)

/**
  * Wrapper class for the time spent by the cpu in each frequency (if dvfs enabled).
  *
  * @author <a href="mailto:aurelien.bourdon@gmail.com">Aurélien Bourdon</a
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
case class TimeInStates(times: Map[Long, Long]) {
  def -(that: TimeInStates) =
    TimeInStates(for ((frequency, time) <- times) yield (frequency, time - that.times.getOrElse(frequency, 0: Long)))
}

/**
  * Wrapper class for the global cpu times.
  * Include idle global cpu time, and the active one.
  *
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
case class GlobalCpuTimes(idleTime: Long, activeTime: Long)

/**
  * Wrapper class for disk info.
  */
case class Disk(name: String, major: Int, minor: Int, bytesRead: Long = 0, bytesWritten: Long = 0) {
  def -(other: Disk): Disk = {
    if (name == other.name && major == other.major && minor == other.minor) {
      val diffBytesRead = bytesRead - other.bytesRead
      val diffBytesWrite = bytesWritten - other.bytesWritten

      Disk(name, major, minor, if (diffBytesRead > 0) diffBytesRead else 0, if (diffBytesWrite > 0) diffBytesWrite else 0)
    }

    else this
  }
}

/**
  * Base trait use for implementing os specific methods.
  *
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
trait OSHelper {
  /**
    * Get the list of frequencies available on the CPU.
    */
  def getCPUFrequencies: Set[Long]

  /**
    * Get the list of processes behind a Target.
    */
  def getProcesses(target: Target): Set[Process]

  /**
    * Get the list of thread behind a Process.
    */
  def getThreads(process: Process): Set[Thread]

  /**
    * Get the process execution time on the cpu.
    *
    * @param process targeted process
    */
  def getProcessCpuTime(process: Process): Long

  /**
    * Get the docker container execution time on the cpu.
    *
    * @param container targeted docker container
    */
  def getDockerContainerCpuTime(container: Container): Long

  /**
    * Get the global execution times for the cpu.
    */
  def getGlobalCpuTimes: GlobalCpuTimes

  /**
    * Get how many time CPU spent under each frequency.
    */
  def getTimeInStates: TimeInStates

  /**
    * Get the target CPU time.
    */
  def getTargetCpuTime(target: Target): Long = {
    target match {
      case process: Process => getProcessCpuTime(process)
      case container: Container => getDockerContainerCpuTime(container)
      case application: Application =>
        getProcesses(application).toSeq.map(process => getProcessCpuTime(process)).sum
      case _ => 0L
    }
  }

  /**
    * Create a cgroup attached to a given subsystem.
    */
  def createCGroup(subsystem: String, name: String): Unit

  /**
    * Test if a cgroup exists.
    */
  def existsCGroup(subsystem: String, name: String): Boolean

  /**
    * Attach pids (`toAttach` string) to an existing cgroup.
    */
  def attachToCGroup(subsystem: String, name: String, toAttach: String): Unit

  /**
    * Delete a cgroup attached to each given subsystem.
    */
  def deleteCGroup(subsystem: String, name: String): Unit

  /**
    * Get information for selected disks.
    */
  def getDiskInfo(names: Seq[String]): Seq[Disk]

  /**
    * Get the global read/written bytes information for selected disks.
    */
  def getGlobalDiskBytes(disks: Seq[Disk]): Seq[Disk]

  /**
    * Get the target read/written bytes information for a given target on selected disks.
    */
  def getTargetDiskBytes(disks: Seq[Disk], target: Target): Seq[Disk]

  /**
    * Get all directories recursively inside a given path.
    */
  def getAllDirectories(dir: File): Seq[File] = {
    val current = dir.listFiles.filter(_.isDirectory)
    current ++ current.flatMap(getAllDirectories)
  }
}

/**
  * OSHelper for UNIX systems.
  *
  * @author <a href="mailto:maxime.colmant@gmail.com">Maxime Colmant</a>
  */
class LinuxHelper extends Configuration(None) with OSHelper {
  /**
    * This file allows to get all the cpu frequencies with the help of procfs and cpufreq_utils.
    */
  lazy val frequenciesPath = load {
    _.getString("powerapi.procfs.frequencies-path")
  } match {
    case ConfigValue(p) if p.contains("%?core") => p
    case _ => "/sys/devices/system/cpu/cpu%?core/cpufreq/scaling_available_frequencies"
  }
  /**
    * This file allows to get all threads associated to one PID with the help of the procfs.
    */
  lazy val taskPath = load {
    _.getString("powerapi.procfs.process-task-path")
  } match {
    case ConfigValue(p) if p.contains("%?pid") => p
    case _ => "/proc/%?pid/task"
  }
  /**
    * Global stat file, giving global information of the system itself.
    * Typically presents under /proc/stat.
    */
  lazy val globalStatPath = load {
    _.getString("powerapi.procfs.global-path")
  } match {
    case ConfigValue(p) => p
    case _ => "/proc/stat"
  }
  /**
    * Process stat file, giving information about the process itself.
    * Typically presents under /proc/[pid]/stat.
    */
  lazy val processStatPath = load {
    _.getString("powerapi.procfs.process-path")
  } match {
    case ConfigValue(p) if p.contains("%?pid") => p
    case _ => "/proc/%?pid/stat"
  }
  /**
    * Time in state file, giving information about how many time CPU spent under each frequency.
    */
  lazy val timeInStatePath = load {
    _.getString("powerapi.sysfs.timeinstates-path")
  } match {
    case ConfigValue(p) => p
    case _ => "/sys/devices/system/cpu/cpu%?index/cpufreq/stats/time_in_state"
  }
  /**
    * Cgroup base directory.
    */
  lazy val cgroupSysFSPath = load {
    _.getString("powerapi.sysfs.cgroup-sysfs-path")
  } match {
    case ConfigValue(p) => p
    case _ => "/sys/fs/cgroup"
  }
  /**
    * Disk stats filepath.
    */
  lazy val diskStatPath = load {
    _.getString("powerapi.procfs.disk-stats-path")
  } match {
    case ConfigValue(p) => p
    case _ => "/proc/diskstats"
  }
  /**
    * CPU's topology.
    */
  lazy val topology: Map[Int, Set[Int]] = load { conf =>
    (for (item: Config <- conf.getConfigList("powerapi.cpu.topology").asScala)
      yield (item.getInt("core"), item.getIntList("indexes").asScala.map(_.toInt).toSet)).toMap
  } match {
    case ConfigValue(values) => values
    case _ => Map()
  }
  /**
    * Mount path.
    */
  lazy val mountsPath = load {
    _.getString("powerapi.procfs.mounts-path")
  } match {
    case ConfigValue(p) => p
    case _ => "/proc/mounts"
  }

  private val log = Logger(classOf[OSHelper])
  private val PSFormat = """^\s*(\d+)\s*""".r
  private val GlobalStatFormat = """cpu\s+([\d\s]+)""".r
  private val TimeInStateFormat = """(\d+)\s+(\d+)""".r
  private val MountFormat = """^.+\s+(.+)\s+(.+)\s+(.+)\s+.+\s+.+$""".r

  /**
    * Returns the mount path of a given cgroup if it exists.
    */
  def cgroupMntPoint(name: String): Option[String] = {
    Source.fromFile(mountsPath).getLines().collectFirst {
      case MountFormat(mnt, typ, tokens) if typ == "cgroup" && tokens.contains(name) =>
        mnt
    }
  }

  def getCPUFrequencies: Set[Long] = {
    (for (index <- topology.values.flatten) yield {
      try {
        using(frequenciesPath.replace("%?core", s"$index"))(source => {
          log.debug("using {} as a frequencies path", frequenciesPath)
          source.getLines.toIndexedSeq(0).split("\\s").map(_.toLong).toSet
        })
      }
      catch {
        case ioe: IOException => log.warn("i/o exception: {}", ioe.getMessage); Set[Long]()
      }
    }).flatten.toSet
  }

  def getProcesses(target: Target): Set[Process] = target match {
    case app: Application =>
      Seq("ps", "-C", app.name, "-o", "pid", "--no-headers").lineStream_!.map {
        case PSFormat(pid) => Process(pid.toInt)
      }.toSet
    case process: Process =>
      Set(process)
    case _ =>
      Set()
  }

  def getThreads(process: Process): Set[Thread] = {
    val pidDirectory = new File(taskPath.replace("%?pid", s"${process.pid}"))

    if (pidDirectory.exists && pidDirectory.isDirectory) {
      /**
        * The pid is removed because it corresponds to the main thread.
        */
      pidDirectory.listFiles.filter(dir => dir.isDirectory && dir.getName != s"${process.pid}").map(dir => Thread(dir.getName.toInt)).toSet
    }
    else Set[Thread]()
  }

  def getProcessCpuTime(process: Process): Long = {
    try {
      using(processStatPath.replace("%?pid", s"${process.pid}"))(source => {
        log.debug("using {} as a procfs process stat path", processStatPath)

        val statLine = source.getLines.toIndexedSeq(0).split("\\s")
        // User time + System time
        statLine(13).toLong + statLine(14).toLong
      })
    }
    catch {
      case ioe: IOException =>
        log.warn("i/o exception: {}", ioe.getMessage)
        0L
    }
  }

  /**
    * Use the cpuacct cgroup to retrieve the overall cpu statistics for a docker container.
    */
  def getDockerContainerCpuTime(container: Container): Long = {
    // TODO: Could be also replaced by a direct call to the Docker API with stream = 0. The main benefit would be to handle a distant docker server.
    cgroupMntPoint("cpuacct") match {
      case Some(path) if new File(s"$path/docker/${container.id}").isDirectory =>
        Source.fromFile(s"$path/docker/${container.id}/cpuacct.stat").getLines().map(_.split("\\s")(1).toLong).sum
      case _ =>
        log.warn("i/o exception, cpuacct cgroup not mounted for the container {}", s"${container.id}")
        0l
    }
  }

  def getGlobalCpuTimes: GlobalCpuTimes = {
    try {
      using(globalStatPath)(source => {
        log.debug("using {} as a procfs global stat path", globalStatPath)

        val cpuTimes = source.getLines.toIndexedSeq(0) match {
          /**
            * Exclude all guest columns, they are already added in utime column.
            *
            * @see http://lxr.free-electrons.com/source/kernel/sched/cputime.c#L165
            */
          case GlobalStatFormat(times) =>
            val idleTime = times.split("\\s")(3).toLong
            val activeTime = times.split("\\s").slice(0, 8).map(_.toLong).sum - idleTime

            GlobalCpuTimes(idleTime, activeTime)
          case _ =>
            log.warn("unable to parse line from {}", globalStatPath)
            GlobalCpuTimes(0, 0)
        }

        cpuTimes
      })
    }
    catch {
      case ioe: IOException =>
        log.warn("i/o exception: {}", ioe.getMessage)
        GlobalCpuTimes(0, 0)
    }
  }

  def getTimeInStates: TimeInStates = {
    val result = collection.mutable.HashMap[Long, Long]()

    for (core <- topology.keys) {
      try {
        using(timeInStatePath.replace("%?index", s"$core"))(source => {
          log.debug("using {} as a sysfs timeinstates path", timeInStatePath)

          for (line: String <- source.getLines) {
            line match {
              case TimeInStateFormat(freq, t) => result += (freq.toLong -> (t.toLong + result.getOrElse(freq.toLong, 0l)))
              case _ => log.warn("unable to parse line {} from file {}", line, timeInStatePath)
            }
          }
        })
      }
      catch {
        case ioe: IOException => log.warn("i/o exception: {}", ioe.getMessage);
      }
    }

    TimeInStates(result.toMap[Long, Long])
  }

  def createCGroup(subsystem: String, name: String): Unit = {
    Seq("cgcreate", "-g", s"$subsystem:/$name").!
  }

  def existsCGroup(subsystem: String, name: String): Boolean = {
    new File(s"$cgroupSysFSPath/$subsystem/$name").exists()
  }

  def attachToCGroup(subsystem: String, name: String, toAttach: String): Unit = {
    Seq("cgclassify", "-g", s"$subsystem:/$name", s"$toAttach").!
  }

  def deleteCGroup(subsystem: String, name: String): Unit = {
    Seq("cgdelete", s"$subsystem:/$name").!
  }

  def getDiskInfo(names: Seq[String]): Seq[Disk] = {
    val Regex = s"\\s+([0-9]+)\\s+([0-9]+)\\s+(${names.mkString("|")})\\s+.*".r

    using(diskStatPath)(source => {
      source.getLines().collect {
        case line => line match {
          case Regex(major, minor, name) => Some(Disk(name, major.toInt, minor.toInt))
          case _ => None
        }
      }.filter(_.isDefined).map(_.get).toList
    })
  }

  def getGlobalDiskBytes(disks: Seq[Disk]): Seq[Disk] = {
    val directory = new File(s"$cgroupSysFSPath/blkio")
    val directories = Seq(directory) ++ getAllDirectories(directory)
    val bytesRead = collection.mutable.Map[String, Long]()
    val bytesWritten = collection.mutable.Map[String, Long]()

    for (dir <- directories) {
      using(s"${dir.getAbsolutePath}/blkio.throttle.io_service_bytes")(source => {
        val lines = source.getLines().toList

        for (disk <- disks) {
          val ReadRegex = s"(${disk.major}):(${disk.minor})\\s+Read\\s+([0-9]+)".r
          val WriteRegex = s"(${disk.major}):(${disk.minor})\\s+Write\\s+([0-9]+)".r

          for (line <- lines) {
            line match {
              case ReadRegex(_, _, bytes) => bytesRead += (disk.name -> (bytesRead.getOrElse(disk.name, 0l) + bytes.toLong))
              case WriteRegex(_, _, bytes) => bytesWritten += (disk.name -> (bytesWritten.getOrElse(disk.name, 0l) + bytes.toLong))
              case _ =>
            }
          }
        }
      })
    }

    disks.map(disk => Disk(disk.name, disk.major, disk.minor, bytesRead.getOrElse(disk.name, 0l), bytesWritten.getOrElse(disk.name, 0l)))
  }

  def getTargetDiskBytes(disks: Seq[Disk], target: Target): Seq[Disk] = {
    val pids = getProcesses(target).map(_.pid)
    val directories = pids.map(pid => new File(s"$cgroupSysFSPath/blkio/powerapi/$pid"))
    val bytesRead = collection.mutable.Map[String, Long]()
    val bytesWritten = collection.mutable.Map[String, Long]()

    for (dir <- directories) {
      using(s"${dir.getAbsolutePath}/blkio.throttle.io_service_bytes")(source => {
        val lines = source.getLines().toList

        for (disk <- disks) {
          val ReadRegex = s"(${disk.major}):(${disk.minor})\\s+Read\\s+([0-9]+)".r
          val WriteRegex = s"(${disk.major}):(${disk.minor})\\s+Write\\s+([0-9]+)".r

          for (line <- lines) {
            line match {
              case ReadRegex(_, _, bytes) => bytesRead += (disk.name -> (bytesRead.getOrElse(disk.name, 0l) + bytes.toLong))
              case WriteRegex(_, _, bytes) => bytesWritten += (disk.name -> (bytesWritten.getOrElse(disk.name, 0l) + bytes.toLong))
              case _ =>
            }
          }
        }
      })
    }

    disks.map(disk => Disk(disk.name, disk.major, disk.minor, bytesRead.getOrElse(disk.name, 0l), bytesWritten.getOrElse(disk.name, 0l)))
  }
}

trait SigarHelperConfiguration extends Configuration {
  /**
    * Sigar native libraries
    */
  lazy val libNativePath = load {
    _.getString("powerapi.sigar.native-path")
  } match {
    case ConfigValue(p) => p
    case _ => "./lib/sigar-bin"
  }
}

/**
  * SIGAR special helper.
  *
  * @author <a href="mailto:l.huertas.pro@gmail.com">Loïc Huertas</a>
  */
class SigarHelper(sigar: SigarProxy) extends OSHelper {
  lazy val cores = sigar.getCpuInfoList()(0).getTotalCores
  private val log = Logger(classOf[SigarHelper])

  def getCPUFrequencies: Set[Long] = throw new SigarException("sigar cannot be able to get CPU frequencies")

  def getProcesses(target: Target): Set[Process] = target match {
    case app if target.isInstanceOf[Application] =>
      Set(ProcessFinder.find(sigar, "State.Name.eq=" + app.asInstanceOf[Application].name).map(l => Process(l.toInt)): _*)
    case _ => Set()
  }

  def getThreads(process: Process): Set[Thread] = throw new SigarException("sigar cannot be able to get process threads")

  def getProcessCpuTime(process: Process): Long = {
    try {
      sigar.getProcTime(process.pid.toLong).getTotal
    }
    catch {
      case se: SigarException =>
        log.warn("sigar exception: {}", se.getMessage)
        0L
    }
  }

  def getDockerContainerCpuTime(container: Container): Long = throw new SigarException("not yet implemented with sigar")

  def getGlobalCpuTimes: GlobalCpuTimes = {
    try {
      val idleTime = sigar.getCpu.getIdle
      val activeTime = sigar.getCpu.getTotal - idleTime

      GlobalCpuTimes(idleTime, activeTime)
    }
    catch {
      case se: SigarException =>
        log.warn("sigar exception: {}", se.getMessage)
        GlobalCpuTimes(0, 0)
    }
  }

  def getTimeInStates: TimeInStates = throw new SigarException("sigar cannot be able to get how many time CPU spent under each frequency")

  def createCGroup(subsystem: String, name: String): Unit = {}

  def existsCGroup(subsystem: String, name: String): Boolean = false

  def attachToCGroup(subsystem: String, name: String, toAttach: String): Unit = {}

  def deleteCGroup(subsystem: String, name: String): Unit = {}

  def getDiskInfo(names: Seq[String]): Seq[Disk] = Seq()

  def getGlobalDiskBytes(disks: Seq[Disk]): Seq[Disk] = Seq()

  def getTargetDiskBytes(disks: Seq[Disk], target: Target): Seq[Disk] = Seq()
}
