package Utils.KudosUtils;

import Utils.ConfigManagement;
import Utils.SQL.DataPlayerGetter;
import de.urbance.Main;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.util.*;

public class KudosLimitation {
    private Main plugin;
    private FileConfiguration config;

    public KudosLimitation() {
        this.plugin = Main.getPlugin(Main.class);
        this.config = ConfigManagement.getConfig();
    }

    public boolean playerReachedLimitation(Player player) {
        int maxKudosToReceive = config.getInt("kudo-award.limitation.max-kudos-to-receive");
        DataPlayerGetter dataPlayerGetter = new DataPlayerGetter();

        try {
            LocalDateTime currentDateTime = LocalDateTime.now();
            LocalDateTime startLimitationDateTime = currentDateTime.minusSeconds(config.getInt("kudo-award.limitation.cooldown"));

            List<LocalDateTime> receivedKudosDateTimeList = dataPlayerGetter.getKudoIDandKudoReceivedDate(player.getUniqueId(), maxKudosToReceive);

            int receivedKudosInCooldown = 0;

            for (LocalDateTime receivedKudoDateTime : receivedKudosDateTimeList) {
                if (receivedKudoDateTime.isAfter(startLimitationDateTime)) {
                    receivedKudosInCooldown += 1;
                }

                if (receivedKudosInCooldown >= maxKudosToReceive) return true;
            }

            return false;

        } catch (NullPointerException exception) {
            plugin.getLogger().warning("An error occurred! Please report the error to the plugin developer:");
            plugin.getLogger().warning("playerUUID: " + player.getUniqueId());

            exception.printStackTrace();
        }

        return true;
    }
}
