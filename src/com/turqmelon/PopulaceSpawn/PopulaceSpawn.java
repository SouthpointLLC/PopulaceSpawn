package com.turqmelon.PopulaceSpawn;

import com.turqmelon.Populace.Resident.Resident;
import com.turqmelon.Populace.Resident.ResidentManager;
import com.turqmelon.Populace.Town.Town;
import com.turqmelon.Populace.Utils.Configuration;
import com.turqmelon.Populace.Utils.Msg;
import com.turqmelon.Populace.Utils.PopulaceTeleport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Creator: Devon
 * Project: PopulaceSpawn
 */
@SuppressWarnings("unchecked")
public class PopulaceSpawn extends JavaPlugin implements Listener {

    private Map<String, Location> permissionSpawnPoints = new HashMap<>();
    private Location newbieSpawn = null;
    private Location defaultSpawn = null;

    @Override
    public void onEnable() {

        File folder = getDataFolder();
        if (!folder.exists()){
            folder.mkdir();
        }

        File file = new File(getDataFolder(), "spawns.json");
        if (!file.exists()){
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else{
            JSONParser parser = new JSONParser();
            try {
                JSONObject object = (JSONObject) parser.parse(new FileReader(file));

                JSONObject d = (JSONObject) object.get("default");
                JSONObject n = (JSONObject) object.get("new");

                this.defaultSpawn = d != null ? jsonToLoc(d) : null;
                this.newbieSpawn = n != null ? jsonToLoc(n) : null;

                JSONArray perms = (JSONArray) object.get("permissions");

                for(Object o : perms){
                    JSONObject p = (JSONObject)o;
                    String perm = (String) p.get("permission");
                    Location location = jsonToLoc((JSONObject) p.get("location"));
                    permissionSpawnPoints.put(perm, location);
                }

            } catch (IOException | ParseException e) {
                e.printStackTrace();
            }
        }

        getServer().getPluginManager().registerEvents(this, this);

    }

    @Override
    public void onDisable() {

        JSONObject data = new JSONObject();
        data.put("default", getDefaultSpawn()!=null?locToJSON(defaultSpawn):null);
        data.put("new", getNewbieSpawn()!=null?locToJSON(newbieSpawn):null);

        JSONArray perms = new JSONArray();
        for(String perm : permissionSpawnPoints.keySet()){
            JSONObject p = new JSONObject();
            p.put("permission", perm);
            p.put("location", locToJSON(permissionSpawnPoints.get(perm)));
            perms.add(p);
        }

        data.put("permissions", perms);

        File file = new File(getDataFolder(), "spawns.json");

        try {
            FileWriter fw = new FileWriter(file);
            fw.write(data.toJSONString());
            fw.flush();
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();
        Resident resident = ResidentManager.getResident(player);
        if (getNewbieSpawn() != null && resident != null && System.currentTimeMillis() - resident.getJoined() < TimeUnit.MINUTES.toMillis(1)){
            new BukkitRunnable(){
                @Override
                public void run(){
                    player.teleport(getNewbieSpawn());
                }
            }.runTaskLater(this, 10L);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onRespawn(PlayerRespawnEvent event){
        Player player = event.getPlayer();
        Location spawn = getSpawnPoint(player, false);
        if (spawn != null){
            event.setRespawnLocation(spawn);
        }
    }

    private Location jsonToLoc(JSONObject object){
        World world = Bukkit.getWorld((String) object.get("world"));
        if (world == null)return null;
        double x = (double) object.get("x");
        double y = (double) object.get("y");
        double z = (double) object.get("z");
        double yaw = (double) object.get("yaw");
        double pitch = (double) object.get("pitch");
        return new Location(world, x, y, z, (float)yaw, (float)pitch);
    }

    private JSONObject locToJSON(Location location){
        JSONObject loc = new JSONObject();
        loc.put("world", location.getWorld().getName());
        loc.put("x", location.getX());
        loc.put("y", location.getY());
        loc.put("z", location.getZ());
        loc.put("yaw", location.getYaw());
        loc.put("pitch", location.getPitch());
        return loc;
    }

    private Location getSpawnPoint(Player player, boolean forceOrigin){

        // Grab the resident so we can check town data
        Resident resident = ResidentManager.getResident(player);
        if (resident == null){
            return null;
        }

        // Is this a new player? Send them to the new player spawn.
        if (System.currentTimeMillis() - resident.getJoined() < TimeUnit.MINUTES.toMillis(1) && getNewbieSpawn() != null){
            return getNewbieSpawn();
        }

        // If a resident is part of a town, they'll always respawn in town - unless they're performing /spawn themselves, then we'll send them to the server spawn
        if (resident.getTown() != null && !forceOrigin){
            Town town = resident.getTown();

            // Not everyone may have permission or the spawn may no longer be valid
            if (town.canWarpToSpawn(resident, false)){

                // We're good, send them to town spawn!
                return town.getSpawn();

            }
        }

        // They're not part of a town, let's do some permission checking
        for(String perm : permissionSpawnPoints.keySet()){
            if (player.hasPermission(perm)){
                return permissionSpawnPoints.get(perm);
            }
        }


        // If all else fails, send them to the default spawn
        return getDefaultSpawn();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (command.getName().equalsIgnoreCase("setspawn")){

            if (sender.hasPermission("populace.spawns.set")){

                if ((sender instanceof Player)){
                    Player player = (Player)sender;

                    if (args.length >= 1){
                        String preference = args[0];

                        boolean unset = false;
                        if (args[args.length-1].equalsIgnoreCase("remove")){
                            unset = true;
                        }

                        if (preference.equalsIgnoreCase("new")){
                            if (unset){
                                this.newbieSpawn = null;
                                player.sendMessage(Msg.OK + "Newbie spawn removed.");
                            }
                            else{
                                this.newbieSpawn = player.getLocation();
                                player.sendMessage(Msg.OK + "Spawn set for new players.");
                            }

                        }
                        else if (preference.equalsIgnoreCase("default")){
                            if (unset){
                                this.defaultSpawn = null;
                                player.sendMessage(Msg.OK + "Default spawn removed.");
                            }
                            else{
                                this.defaultSpawn = player.getLocation();
                                player.sendMessage(Msg.OK + "Default spawn set.");
                            }
                        }
                        else{
                            if (unset && permissionSpawnPoints.containsKey(preference)){
                                permissionSpawnPoints.remove(preference);
                                player.sendMessage(Msg.OK + "Default spawn for users with the \"" + preference  + "\" permission removed.");
                            }
                            else{
                                permissionSpawnPoints.put(preference, player.getLocation());
                                player.sendMessage(Msg.OK + "Default spawn set for users with the \"" + preference + "\" permission.");
                            }
                        }
                    }
                    else{
                        sender.sendMessage(Msg.INFO + "Usage: /setspawn <Spawn> [unset]");
                    }

                }
                else{
                    sender.sendMessage(Msg.ERR + "Must be a player.");
                }

            }
            else{
                sender.sendMessage(Msg.ERR + "You don't have permission.");
            }

            return true;

        }
        else if (command.getName().equalsIgnoreCase("spawn")){

            if (sender.hasPermission("populace.spawns.spawn")){

                if ((sender instanceof Player)){
                    Player player = (Player)sender;

                    Location spawn = getSpawnPoint(player, true);

                    if (sender.hasPermission("populace.spawns.specific") && args.length == 1){
                        String preference = args[0];
                        if (preference.equalsIgnoreCase("new")){
                            spawn = getNewbieSpawn();
                        }
                        else if (preference.equalsIgnoreCase("default")){
                            spawn = getDefaultSpawn();
                        }
                        else if (preference.equalsIgnoreCase("town")){
                            spawn = getSpawnPoint(player, false);
                        }
                        else if (permissionSpawnPoints.containsKey(preference)) {
                            spawn = permissionSpawnPoints.get(preference);
                        }
                        else{
                            sender.sendMessage(Msg.ERR + "Specify which spawn point:");
                            sender.sendMessage(Msg.ERR + " - New");
                            sender.sendMessage(Msg.ERR + " - Default");
                            sender.sendMessage(Msg.ERR + " - Town");
                            for(String perm : permissionSpawnPoints.keySet()){
                                sender.sendMessage(Msg.ERR + " - " + perm);
                            }
                            return true;
                        }
                    }

                    if (spawn != null){
                        player.sendMessage(Msg.OK + "Teleporting to spawn...");
                        new PopulaceTeleport(player, spawn, player.getLocation(), Configuration.TELEPORT_WARMUP_TIME, Configuration.TELEPORT_COOLDOWN_TIME, false);
                    }
                    else{
                        sender.sendMessage(Msg.ERR + "Spawn has not been set.");
                    }

                }
                else{
                    sender.sendMessage(Msg.ERR + "Must be a player.");
                }

            }
            else{
                sender.sendMessage(Msg.ERR + "You don't have permission.");
            }

            return true;
        }

        return true;
    }

    public Location getNewbieSpawn() {
        return newbieSpawn;
    }

    public Location getDefaultSpawn() {
        return defaultSpawn;
    }
}
