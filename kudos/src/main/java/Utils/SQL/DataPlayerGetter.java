package Utils.SQL;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

public class DataPlayerGetter {

    public List<LocalDateTime> getKudoIDandKudoReceivedDate(UUID fromPlayer, int amountLastKudos) {
        List<LocalDateTime> receivedKudoDateTimeList = new ArrayList<>();

        try (Connection connection = SQL.getConnection(); PreparedStatement preparedStatement = connection.prepareStatement("SELECT KudoID, Date FROM `kudos` WHERE AwardedToPlayer=? ORDER BY KudoID DESC LIMIT ?;")) {
            preparedStatement.setString(1, fromPlayer.toString());
            preparedStatement.setInt(2, amountLastKudos);

            ResultSet results = preparedStatement.executeQuery();

            while (results.next()) {
                LocalDateTime receivedKudoDateTime = LocalDateTime.parse(results.getString("Date"));

                receivedKudoDateTimeList.add(receivedKudoDateTime);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return receivedKudoDateTimeList;
    }

}