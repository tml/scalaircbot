package ircbot.modules

import scala.collection.mutable.HashMap

import ircbot._
import helpers.Auth
import helpers.Commands

class MonitorHashPHP(ctl: Control) extends Module(ctl) with Auth with Commands {
    val channel = "##php"
    val floodTimespan = 4
    val floodThreshold = 5
    val profanityTimespan = 2880
    val profanityThreshold = 3

    val messages = new HashMap[String, List[Long]]()
    val profanity = new HashMap[String, List[(String, Long)]]()

    var lastCleanup = 0

    def handleMessage(msg: Message) = msg match {
        case Msg(from, to, msg) =>
            if (to equals channel) {
                // Check for profanity
                if (isProfanity(msg)) {
                    addProfanity(from.nick, msg)
                    if (isAbusingProfanity(from.nick)) {
                        mute(from, Minutes(30), "to prevent profanity abuse")
                    } else {
                        ctl.p.msg(from.nick, "Please keep the profanity out of "+channel+", thanks.")
                    }
                }


                if (!isGranted(ctl, from, Normal, Manager, Administrator)) {
                    // checks that no nick sends more than <floodThreshold> msg per <floodTimespan> sec
                    addMessage(from.nick)

                    if (isFlooding(from.nick)) {
                        mute(from, Minutes(5), "to prevent them from flooding the channel more")
                        messages -= from.nick
                    }

                }

                cleanup

                true
            } else {
                words(msg, 3) match {
                    case "!profanity" :: "add" :: word :: Nil =>
                        if (isGranted(ctl, from, Manager, Administrator)) {
                            val updated = ctl.db.prepareStatement("INSERT INTO irc_profanity SET word = ?", word).executeUpdate
                            ctl.p.msg(from.nick, "Word '"+word+"' registered as profanity.")
                        } else {
                            ctl.p.msg(from.nick, "Permission denied.")
                        }
                        false

                    case "!profanity" :: "remove" :: word :: Nil =>
                        if (isGranted(ctl, from, Manager, Administrator)) {
                            val upadted = ctl.db.prepareStatement("DELETE FROM irc_profanity WHERE word = ?", word).executeUpdate
                            if (upadted > 0) {
                                ctl.p.msg(from.nick, "Word '"+word+"' removed as profanity.")
                            } else {
                                ctl.p.msg(from.nick, "Word '"+word+"' not found.")
                            }
                        } else {
                            ctl.p.msg(from.nick, "Permission denied.")
                        }
                        false

                    case "!profanity" :: "list" :: Nil =>
                        if (isGranted(ctl, from, Manager, Administrator)) {
                            val results = ctl.db.prepareStatement("SELECT DISTINCT word FROM irc_profanity").executeQuery
                            var l: List[String] = Nil;
                            for (r <- results) l = l ::: r.getString("word") :: Nil
                            ctl.p.msg(from.nick, "List: "+l.mkString(", "))
                        } else {
                            ctl.p.msg(from.nick, "Permission denied.")
                        }
                        false
                    case _ => true
                }
            }
        case _ => true
    }

    def addMessage(nick: String) = messages(nick) = (messages get nick match {
        case Some(msgs) =>  System.currentTimeMillis :: msgs
        case None => System.currentTimeMillis :: Nil
    })

    def addProfanity(nick: String, msg: String) = profanity(nick) = (profanity get nick match {
        case Some(msgs) =>  (msg, System.currentTimeMillis) :: msgs
        case None => (msg, System.currentTimeMillis) :: Nil
    })

    def isFlooding(nick: String) = messages get nick match {
        case Some(msgs) => msgs.filter{ _ > System.currentTimeMillis-floodTimespan*1000 }.length >= floodThreshold
        case None => false
    }

    def isUsingProfanity(nick: String, threshold: Int) = profanity get nick match {
        case Some(msgs) => msgs.filter{ _._2 > System.currentTimeMillis-profanityTimespan*1000 }.length == threshold
        case None => false
    }

    def isAbusingProfanity(nick: String): Boolean = isUsingProfanity(nick, profanityThreshold)

    def isProfanity(msg: String) = {
        try {
            val ws = words(msg.replaceAll("[^a-zA-Z0-9 ]+", ""))
            val stmt = ctl.db.prepareStatement("SELECT word FROM irc_profanity WHERE word IN ("+(ws.map(x =>"?").mkString(", "))+") LIMIT 1", ws)
            val results = stmt.executeQuery

            val rowcount = ctl.db.prepareStatement("SELECT FOUND_ROWS() as number").executeQuery.firstRow.getInt("number")

            stmt.close

            rowcount > 0
        } catch {
            case ex: Exception =>
                ctl.db.handleException(ex)
                false
        }
    }

    def cleanup = {
        if (lastCleanup > 100) {
            for ( entry <- messages) entry match {
                case (nick, msgs) =>
                    val newMsgs = msgs filter { _ < System.currentTimeMillis-floodTimespan*1000 }
                    if (newMsgs.length == 0) {
                        messages -= nick
                    } else {
                        messages(nick) = newMsgs
                    }
            }

            for ( entry <- profanity) entry match {
                case (nick, msgs) =>
                    val newMsgs = msgs filter{ _._2 < System.currentTimeMillis-profanityTimespan*1000 };
                    if (newMsgs.length == 0) {
                        profanity -= nick
                    } else {
                        profanity(nick) = newMsgs
                    }
            }

            lastCleanup = 0
        } else {
            lastCleanup += 1
        }
    }


    def mute(prefix: Prefix, duration: Duration, reason: String) = { 
        ctl.chanserv.mute(channel, prefix, duration)
        ctl.p.msg(channel, prefix.nick + " has been muted for "+duration+" "+reason+".")
    }
}
