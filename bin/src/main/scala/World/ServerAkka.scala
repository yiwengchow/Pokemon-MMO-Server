package World

import akka.actor.{ActorSystem, Actor, ActorRef, Props}
import akka.remote.DisassociatedEvent
import akka.routing.{Router,ActorRefRoutee,FromConfig}
import com.typesafe.config.ConfigFactory
import scala.collection.mutable.{ArrayBuffer,ListMap}
import java.io.{File, BufferedReader, FileReader, PrintWriter, ObjectInputStream, ObjectOutputStream, 
  FileInputStream, FileOutputStream}
import Messages._
import OutMessages._
import WorldObject._
import scala.util.control.Breaks
import scala.util.Random
import scala.io.StdIn

object ServerAkka extends App{
  new File("players").mkdir
  val players = new File("players").listFiles
  for(x <- players){
    val reader = new BufferedReader(new FileReader(x.toString + "/pass.txt"))
    val pass = reader.readLine
    reader.close()
    val writer = new PrintWriter(new File(s"${x.toString}/pass.txt"))
    writer.println(pass.split(" ")(0) + " offline")
    writer.close()
  }
  val config = ConfigFactory.parseFile(new File("application.conf"))
  val fileMain = new File("players").toString
  val system = ActorSystem("PukimanServer", config)
  val clientList = new ListMap[(String,Int),ArrayBuffer[ClientInfo]]
  clientList.put(("battle", 0), new ArrayBuffer[ClientInfo]())
  val waitingBattleList = ArrayBuffer[(ClientInfo,ClientInfo,Pokimon, String)]()
  val movementServerActor = system.actorOf(Props(classOf[MovementRouter], clientList), "MovementServer")
  val battleServerActor = system.actorOf(Props(classOf[BattleRouter], clientList, waitingBattleList), "BattleServer")
  val loginServerActor = system.actorOf(Props(classOf[LoginRouter], fileMain), "LoginServer")
  Thread.sleep(10)
  print("Type \"end\" to end the server: ")
  while(!StdIn.readLine().toLowerCase.equals("end")){
    print("Type \"end\" to end the server: ")
  }
  system.terminate
}

class ClientInfo(val clientName : String, var clientCoordsX : Int, var clientCoordsY : Int, val clientActor: ActorRef)

class LoginRouter(mainDir: String) extends Actor {
//  val router = {
//    val routee = Vector.fill(5) {
//      val r = context.actorOf(Props(classOf[LoginServer], mainDir))
//      context.watch(r);
//      ActorRefRoutee(r);
//    }
//    Router(RoundRobinRoutingLogic(), routee);
//  }
  val router = context.actorOf(FromConfig.props(Props(classOf[LoginServer], mainDir)), "LoginRouter")
  
  
  override def receive = {
    case Login(name, password) =>
      router.tell(Login(name, password), sender)
    case Register(name, password) =>
      router.tell(Register(name, password), sender)
    case Synchronizer(player) =>
      router.tell(Synchronizer(player), self)
    case Logout(name, mapType, mapNum) =>
      router.tell(Logout(name, mapType, mapNum), sender)
  }
}

class LoginServer(mainDir: String) extends Actor {
  var client : ClientInfo = null
  
  override def receive = {
    case Login(name, password) =>{
      println("login")
      if(new File(s"$mainDir/$name").exists){
        val playerFolder = new File(s"$mainDir/$name")
        val reader = new BufferedReader(new FileReader(s"$mainDir/$name/pass.txt"))
        val pass = reader.readLine
        val strings = pass.split(" ")
        if(!password.equals(strings(0))){
          sender ! FailLogin("Wrong ID or password")
        }
        else if(strings(1).equals("online")){
          sender ! FailLogin("Already online")
        }
        else{
          val writer = new PrintWriter(new File(s"$mainDir/$name/pass.txt"))
          writer.println(pass.split(" ")(0) + " online")
          writer.close()
          val oInputStream = new ObjectInputStream(new FileInputStream(new File(s"$mainDir/$name/player.srl")))
          val playerObject = oInputStream.readObject.asInstanceOf[Player]
          oInputStream.close();
          sender ! OutSynchronizer(playerObject)
        }
        reader.close()
      }
      else{
        sender ! FailLogin("Wrong ID or password")
      }
    }
    case Register(name, password) => {
      if(new File(s"$mainDir/$name").exists){
        sender ! FailRegister
      }
      else{
        new File(s"$mainDir/$name").mkdir
        val playerFolder = new File(new File(s"$mainDir/$name").toString+"/pass.txt")
        val writer = new PrintWriter(playerFolder)
        writer.println(password + " online")
        writer.close()
        sender ! Registered(name)
      }
    }
    case Synchronizer(player) => {
      val oos = new ObjectOutputStream(new FileOutputStream(s"$mainDir/${player.name}/player.srl"))
      oos.writeObject(player)
      oos.close()
    }
    case Logout(name, mapType, mapNum) => {
      println("logout")
      sender ! LogoutSuccessful
      val reader = new BufferedReader(new FileReader(s"$mainDir/$name/pass.txt"))
      val pass = reader.readLine
      reader.close()
      val writer = new PrintWriter(new File(s"$mainDir/$name/pass.txt"))
      writer.println(pass.split(" ")(0) + " offline")
      writer.close()
      val clients = ServerAkka.clientList(mapType,mapNum)
      
      val size = clients.length
      var x = 0
        
      while (x < size){
        if (clients(x).clientName.equals(name)){
          client = clients(x)
          clients.remove(x)
          x = size
        }
        else{
          x += 1 
        }
      }
      
      for (x <- clients){//start changeMap
        if (!x.clientName.equals(client.clientName)){
          x.clientActor ! OutUpdate(client.clientName)//update previous map to delete player
        }
      }
    }
  }
}

class BattleRouter(clientList: ListMap[(String,Int),ArrayBuffer[ClientInfo]],
    waitingBattleList: ArrayBuffer[(ClientInfo,ClientInfo,Pokimon, String)]) extends Actor{
//  val router = {
//    val routee = Vector.fill(5) {
//      val r = context.actorOf(Props(classOf[BattleServer], battleSystemArr, waitingBattleList))
//      context.watch(r);
//      ActorRefRoutee(r);
//    }
//    Router(RoundRobinRoutingLogic(), routee);
//  }
  
  val router = context.actorOf(FromConfig.props(Props(classOf[BattleServer], clientList, waitingBattleList)), "BattleRouter")
  
  override def receive ={
    case BattleDetails(receipientName, poki, mapNum, mapType) =>
      router.tell(BattleDetails(receipientName, poki, mapNum, mapType), self)
    case BattleChoice(name, oppName, choice, poki, mapNum, mapType) => 
      router.tell(BattleChoice(name, oppName, choice, poki, mapNum, mapType), self)
    case ChangePokemon(oppName, poki, mapNum, mapType) =>
      router.tell(ChangePokemon(oppName, poki, mapNum, mapType), self)
    case EndBattle(name: String, oppName: String, mapNum, mapType) => 
      router.tell(EndBattle(name, oppName, mapNum, mapType), self)
  }
}

class BattleServer(clientList: ListMap[(String,Int),ArrayBuffer[ClientInfo]], 
    waitingBattleList: ArrayBuffer[(ClientInfo,ClientInfo,Pokimon, String)]) extends Actor{
  val breakableLoop = new Breaks
  var counter = 0
  
  override def receive = {
    case BattleDetails(receipientName, poki, mapNum, mapType) =>{
      val buffer = clientList((mapType, mapNum))
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(receipientName)){
            x.clientActor.tell(OutBattleDetails(poki),sender)
            breakableLoop.break
          }
        }
      }
    }
    case BattleChoice(name, oppName, choice, poki, mapNum, mapType) =>{
      val buffer = clientList((mapType, mapNum))
      var sent = false
      var player1: ClientInfo = null
      var player2: ClientInfo = null
      var index = 0
      var found = 0
      counter+=1
      
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(name)){
            player1 = x
            found+=1
          }
          if(x.clientName.equals(oppName)){
            player2 = x
            found+=1
          }
          if(found == 2){
            breakableLoop.break
          }
        }
      }
      
      var pokimon2nd: Pokimon = null
      var choice2nd = ""
      
      waitingBattleList.synchronized{
        breakableLoop.breakable{
          for(x <- waitingBattleList){
            if(x._2.clientName.equals(name)){
              pokimon2nd = x._3
              choice2nd = x._4
              waitingBattleList.remove(index)
              sent = true
              breakableLoop.break
            }
            index+=1
          }
        }
        if(!sent){
          waitingBattleList.append((player1,player2,poki,choice))
        }
      }
      
      if(sent){
        val outcome = processBattle((name,poki),(oppName,pokimon2nd),choice,choice2nd)
        player1.clientActor.tell(outcome, sender)
        player2.clientActor.tell(outcome, sender)
      }
      
    }
    case ChangePokemon(oppName, poki, mapNum, mapType) => {
      val buffer = clientList((mapType, mapNum))
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(oppName)){
            x.clientActor.tell(OutChangePokimon(poki), sender)
            breakableLoop.break
          }
        }
      }
    }
    case EndBattle(name, oppName, mapNum, mapType) => {
      val buffer = clientList((mapType, mapNum))
      var player1: ClientInfo = null
      var player2: ClientInfo = null
      var found = 0
      println(s"End battle by : $name")
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(name)){
            player1 = x
            found+=1
          }
          if(x.clientName.equals(oppName)){
            player2 = x
            found+=1
          }
          if(found == 2){
            breakableLoop.break
          }
        }
      }
      player1.clientActor.tell(OutEndBattle(name), sender)
      player2.clientActor.tell(OutEndBattle(name), sender)
    }
  }
  
  def errorProneStatement (dmgTakePk: Pokimon, dmgDealPk: Pokimon, dmgDealSkill: Int): Double = {
    try{
      dmgTakePk.takeDmg(dmgDealPk.doDmg(dmgTakePk.element, dmgDealSkill))
    }
    catch{
      case e: NoSuchElementException =>{
        val nullskill = dmgDealPk.skillSet(dmgDealSkill)
        if(nullskill.power < 0 && nullskill.description.contains("defense")){
          dmgTakePk.debuffSkill(true, nullskill.power)
        }
        else if(nullskill.power > 0 && nullskill.description.contains("defense")){
          dmgDealPk.buffSkill(true, nullskill.power)
        }
        else if(nullskill.power < 0 && nullskill.description.contains("attack")){
          dmgTakePk.debuffSkill(false, nullskill.power)
        }
        else if(nullskill.power > 0 && nullskill.description.contains("attack")){
          dmgDealPk.buffSkill(false, nullskill.power)
        }
        nullskill.power.toDouble
      }
    }
  }
  
  def processBattle(player1: (String,Pokimon), player2: (String,Pokimon),
      player1Choice: String, player2Choice: String): OutBattleResult ={
    if(player1Choice.equals("pokemon")^player2Choice.equals("pokemon")){
      if(player1Choice.equals("pokemon")&&player2Choice.contains("fight")){
        val damage = errorProneStatement(player1._2, player2._2, player2Choice.split(" ")(1).toInt)
        if(player1._2.health <= 0){
          OutBattleResultDead(player1._1,player1._2,player2._2, player1Choice, player2Choice, -1,
              player2Choice.split(" ")(1).toInt, damage, 0)
        }
        else{
          OutBattleResultAlive(player1._1,player1._2,player2._2, player1Choice, player2Choice, -1,
              player2Choice.split(" ")(1).toInt, damage, 0)
        }
      }
      else{
        val damage = errorProneStatement(player2._2, player1._2, player1Choice.split(" ")(1).toInt)
        if(player2._2.health <= 0){
          OutBattleResultDead(player2._1,player2._2,player1._2, player2Choice, player1Choice,
              -1, player1Choice.split(" ")(1).toInt, damage, 0)
        }
        else{
          OutBattleResultAlive(player2._1,player2._2,player1._2, player2Choice, player1Choice,
              -1, player1Choice.split(" ")(1).toInt, damage, 0)
        }
      }
    }
    else{
      if(player1Choice.contains("fight")&&player2Choice.contains("fight")){
        if(player1._2.speed > player2._2.speed){
          val damage = errorProneStatement(player2._2, player1._2, player1Choice.split(" ")(1).toInt)
          if(player2._2.health <= 0){
            OutBattleResultDead(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage, 0)
          }
          else{
            val damage1 = errorProneStatement(player1._2, player2._2, player2Choice.split(" ")(1).toInt)
            if(player1._2.health <= 0){
              OutBattleResultDead(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                  player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage1, damage)
            }
            else{
              OutBattleResultAlive(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                  player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage1, damage)
            }
          }
        }
        else if(player1._2.speed < player2._2.speed){
          val damage = errorProneStatement(player1._2, player2._2, player2Choice.split(" ")(1).toInt)
          if(player1._2.health <= 0){
            OutBattleResultDead(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage, 0)
          }
          else{
            val damage1 = errorProneStatement(player2._2, player1._2, player1Choice.split(" ")(1).toInt)
            if(player2._2.health <= 0){
              OutBattleResultDead(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage1, damage)
            }
            else{
              OutBattleResultAlive(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage1, damage)
            }
          }
        }
        else{
          if(Random.nextBoolean){
            val damage = errorProneStatement(player2._2, player1._2, player1Choice.split(" ")(1).toInt)
            if(player2._2.health <= 0){
              OutBattleResultDead(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage, 0)
            }
            else{
              val damage1 = errorProneStatement(player1._2, player2._2, player2Choice.split(" ")(1).toInt)
              if(player1._2.health <= 0){
                OutBattleResultDead(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                  player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage1, damage)
              }
              else{
                OutBattleResultAlive(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                  player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage1, damage)
              }
            }
          }
          else{
            val damage = errorProneStatement(player1._2, player2._2, player2Choice.split(" ")(1).toInt)
            if(player1._2.health <= 0){
              OutBattleResultDead(player1._1,player1._2,player2._2, player1Choice, player2Choice,
                player1Choice.split(" ")(1).toInt, player2Choice.split(" ")(1).toInt, damage, 0)
            }
            else{
              val damage1 = errorProneStatement(player2._2, player1._2, player1Choice.split(" ")(1).toInt)
              if(player2._2.health <= 0){
                OutBattleResultDead(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage1, damage)
              }
              else{
                OutBattleResultAlive(player2._1,player2._2,player1._2, player2Choice, player1Choice,
                player2Choice.split(" ")(1).toInt, player1Choice.split(" ")(1).toInt, damage1, damage)
              }
            }
          }
        }
      }
      else{
        OutBattleResultAlive(player2._1,player2._2,player1._2, player2Choice, player1Choice,
            -1, -1, 0, 0)
      }
    }
  }
}

class MovementRouter(clientList: ListMap[(String,Int),ArrayBuffer[ClientInfo]]) extends Actor{
//  val router = {
//    val routee = Vector.fill(5) {
//      val r = context.actorOf(Props(classOf[MovementServer], clientList))
//      context.watch(r);
//      ActorRefRoutee(r);
//    }
//    Router(RoundRobinRoutingLogic(), routee);
//  }
  
  val router = context.actorOf(FromConfig.props(Props(classOf[MovementServer], clientList)), "MovementRouter")
  
  override def preStart = {
    context.system.eventStream.subscribe(self, classOf[akka.remote.DisassociatedEvent])
  }
  
  override def receive = {
    case DisassociatedEvent(localAddress, remoteAddress, _) => {
      var client : ClientInfo = null
      var map : (String,Int) = null
      var clients : ArrayBuffer[ClientInfo] = null
    
      for (x <- clientList.keys){
        for (y <- clientList(x)){
          if(y.clientActor.path.address.equals(remoteAddress)){
            client = y
            map = x
            clients = clientList(x)
          }
        }
      }
      
      if (clients != null){
        val reader = new BufferedReader(new FileReader(s"${ServerAkka.fileMain}/${client.clientName}/pass.txt"))
        val pass = reader.readLine
        reader.close()
        val writer = new PrintWriter(new File(s"${ServerAkka.fileMain}/${client.clientName}/pass.txt"))
        writer.println(pass.split(" ")(0) + " offline")
        writer.close()
        for (x <- clients){
          if (!x.clientName.equals(client.clientName))
            x.clientActor ! OutUpdate(client.clientName)
        }
        
        var count = 0
        var size = clients.size
        
        while (count < clients.size){
          if (clients(count).equals(client)){
            clients.remove(count)
            count = size
          }
          else{
            count += 1
          }
        }
      }
    }
    case MovementMsg(name, move, coordsX, coordsY, mapNum, mapType) =>
      router.tell(MovementMsg(name, move, coordsX, coordsY, mapNum, mapType), self)
    case Message(name, mapNum, mapType, msg) =>
      router.tell(Message(name, mapNum, mapType, msg), self)
    case MapChange(initialize, name, coordsX, coordsY, mapNum, mapType, pMapNum, pMapType) =>
      router.tell(MapChange(initialize, name, coordsX, coordsY, mapNum, mapType, pMapNum, pMapType), sender)
    case BattleReq(toReqName, name, mapNum, mapType) =>
      router.tell(BattleReq(toReqName, name, mapNum, mapType), self)
    case BattleReqAccepted(name, receipientName, pMapNum, pMapType) =>
      router.tell(BattleReqAccepted(name, receipientName, pMapNum, pMapType), self)
    case RejectBattleReq(name: String, mapNum: Int, mapType: String, reason: String) =>
      router.tell(RejectBattleReq(name: String, mapNum: Int, mapType: String, reason: String), self)
  }
}

class MovementServer(clientList: ListMap[(String,Int),ArrayBuffer[ClientInfo]]) extends Actor{
  val breakableLoop = new Breaks
  var client : ClientInfo = null
  var update = false
  var counter = 0
  
  override def receive = {
    case MovementMsg(name, move, coordsX, coordsY, mapNum, mapType) =>{
      counter+=1
      try{
        var clients = clientList.getOrElse((mapType,mapNum),null)
        var client : ClientInfo = null // start getClient
        
        var size = clients.length
        var x = 0
        
        while (x < size){
          if (clients(x).clientName.equals(name)){
            clients(x).clientCoordsX = coordsX
            clients(x).clientCoordsY = coordsY
            client = clients(x)
            x = size
          }
          else{
            x+=1
          }
        }
        
        for (x <- clients){// start Send
          if (!x.clientName.equals(client.clientName)){
            x.clientActor.tell(OutMovement(false, name, move, coordsX, coordsY), sender)
          }
        } // end Send
      }
      catch{
        case e : NullPointerException =>
      }
    }
    case Message(name, mapNum, mapType, msg) =>{
      var clients = clientList.getOrElse((mapType,mapNum), null)
      var client : ClientInfo = null
      
      for (x <- clients){
        if (x.clientName.equals(name)){
          client = x
        }
      }
      
      for (x <- clients){// start Send
        if (!x.clientName.equals(client.clientName)){
          x.clientActor.tell(OutMessage(name, mapNum, mapType, msg), sender)
        }
      }//end Send
    }
    case MapChange(initialize, name, coordsX, coordsY, mapNum, mapType, pMapNum, pMapType) => {
      val pClients = clientList.getOrElse((pMapType,pMapNum), null)
      var clients = clientList.getOrElse((mapType,mapNum), null)
      var client : ClientInfo = null
      
      var login = false
      
      if (mapNum.equals(pMapNum) && mapType.equals(pMapType)){
        login = true
      }
      
      if (clients == null){
        clientList.put((mapType,mapNum), new ArrayBuffer[ClientInfo]())
        clients = clientList.getOrElse((mapType,mapNum), null)
      }
      
      if (!login){
        val size = pClients.length
        var x = 0
        
        while (x < size){
          if (pClients(x).clientName.equals(name)){
            client = pClients(x)
            pClients.remove(x)
            x = size
          }
          else{
            x += 1 
          }
        }
      
        for (x <- pClients){//start changeMap
          if (!x.clientName.equals(client.clientName)){
            x.clientActor ! OutUpdate(client.clientName)//update previous map to delete player
          }
        }
      }
      
      else{
        val size = clients.length
        var x = 0
        
        while (x < size){
          if (clients(x).clientName.equals(name)){
            clients(x).clientCoordsX = coordsX
            clients(x).clientCoordsY = coordsY
            client = clients(x)
            x = size
          }
          else{
            x += 1 
          }
        }
        
        if (client == null){
          client = new ClientInfo(name, coordsX, coordsY, sender) 
        }
      }
      
      clients.append(client)
      
      for (x <- clients){
        if (!x.clientName.equals(client.clientName)){
          if (!mapType.equals("battle")){
            sender ! OutMovement(initialize, x.clientName, "stopdown", x.clientCoordsX, x.clientCoordsY)
          }
        }
      }
      
      context.parent ! MovementMsg(client.clientName, "stopDown", coordsX, coordsY, mapNum, mapType)
      
    }
    case BattleReq(toReqName, name, mapNum, mapType) => {
      val buffer = clientList((mapType,mapNum))
      var needs: ClientInfo = null
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(toReqName)){
            x.clientActor.tell(OutBattleReq(name), sender)
            breakableLoop.break
          }
        }
      }
    }
    case BattleReqAccepted(name, receipientName, pMapNum, pMapType) =>{
      val buffer = clientList((pMapType,pMapNum))
      var needs: ClientInfo = null
      var index = 0
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(receipientName)){
            x.clientActor.tell(OutBattleReqAccepted, sender)
            index+=1
          }
          if(x.clientName.equals(name)){
            x.clientActor.tell(OutBattleReqAccepted, sender)
            index+=1
          }
          if(index == 2){
            breakableLoop.break
          }
        }
      }
    }
    
    case RejectBattleReq(name: String, mapNum: Int, mapType: String, reason: String) => {
      val buffer = clientList((mapType,mapNum))
      breakableLoop.breakable{
        for(x <- buffer){
          if(x.clientName.equals(name)){
            x.clientActor.tell(OutRejectBattleReq(reason), sender)
            breakableLoop.break
          }
        }
      }
    }
    
    case meh: Any=> 
      println(meh.getClass())
  }
  
  
}

