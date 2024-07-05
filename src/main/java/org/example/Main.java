package org.example;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.request.*;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.GetChatMemberResponse;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class Main {
    private static final String BOT_TOKEN = "6821262197:AAHI_3eJCmmYv7ozDGf7MTsu9sgiht3MASo";
    private static final long ADMIN_ID = 123456789L; // Admin ID
    private static final String[] REQUIRED_CHANNELS = {"@teste1234gwge"}; // Majburiy qo'shilishi kerak bo'lgan kanallar
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/bot";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "1234";

    private static final Map<Long, String> userSteps = new HashMap<>();
    private static final Map<Long, User> userLogins = new HashMap<>();

    public static void main(String[] args) {
        TelegramBot bot = new TelegramBot(BOT_TOKEN);

        bot.setUpdatesListener(updates -> {
            for (Update update : updates) {
                if (update.message() != null && update.message().text() != null) {
                    long chatId = update.message().chat().id();
                    String text = update.message().text();

                    if (text.startsWith("/start")) {
                        if (areAllChannelsJoined(bot, chatId)) {
                            if (isUserRegistered(chatId)) {
                                sendWelcomeBackMessage(bot, chatId);
                            } else {
                                sendRegistrationRequest(bot, chatId);
                            }
                        } else {
                            sendJoinChannelsMessage(bot, chatId);
                        }
                    } else if (text.startsWith("/admin") && chatId == ADMIN_ID) {
                        handleAdminCommands(bot, chatId, text);
                    } else if (text.startsWith("/average")) {
                        sendAverageGrade(bot, chatId);
                    } else if (userSteps.containsKey(chatId)) {
                        String step = userSteps.get(chatId);
                        handleUserSteps(bot, chatId, text, step);
                    }
                } else if (update.callbackQuery() != null) {
                    long chatId = update.callbackQuery().message().chat().id();
                    String data = update.callbackQuery().data();

                    if (data.equals("check")) {
                        if (areAllChannelsJoined(bot, chatId)) {
                            if (isUserRegistered(chatId)) {
                                sendWelcomeBackMessage(bot, chatId);
                            } else {
                                sendRegistrationRequest(bot, chatId);
                            }
                        } else {
                            sendJoinChannelsMessage(bot, chatId);
                        }
                    } else {
                        handleUserSteps(bot, chatId, data, "subject");
                    }

                    // Callback queryni javobini berish uchun:
                    bot.execute(new AnswerCallbackQuery(update.callbackQuery().id()));
                }
            }
            return UpdatesListener.CONFIRMED_UPDATES_ALL;
        });
    }

    private static boolean areAllChannelsJoined(TelegramBot bot, long chatId) {
        for (String channel : REQUIRED_CHANNELS) {
            GetChatMemberResponse response = bot.execute(new GetChatMember(channel, chatId));
            if (response.chatMember() == null) {
                System.out.println("Failed to get chat member status for channel: " + channel + " and chatId: " + chatId);
                return false;
            }
            String status = String.valueOf(response.chatMember().status());
            System.out.println("User status in channel " + channel + ": " + status);
            if (!(status.equals("member") || status.equals("administrator") || status.equals("creator"))) {
                return false;
            }
        }
        return true;
    }


    private static boolean isUserRegistered(long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM users WHERE chat_id = ?")) {
            stmt.setLong(1, chatId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void sendRegistrationRequest(TelegramBot bot, long chatId) {
        SendMessage request = new SendMessage(chatId, "Ro'yxatdan o'tish uchun ismingizni kiriting:")
                .parseMode(ParseMode.HTML);
        bot.execute(request);
        userSteps.put(chatId, "first_name");
    }

    private static void handleUserSteps(TelegramBot bot, long chatId, String text, String step) {
        switch (step) {
            case "first_name":
                User user = new User();
                user.setFirstName(text);
                userLogins.put(chatId, user);
                SendMessage requestLastName = new SendMessage(chatId, "Familiyangizni kiriting:")
                        .parseMode(ParseMode.HTML);
                bot.execute(requestLastName);
                userSteps.put(chatId, "last_name");
                break;

            case "last_name":
                userLogins.get(chatId).setLastName(text);
                SendMessage requestEmail = new SendMessage(chatId, "Emailingizni kiriting:")
                        .parseMode(ParseMode.HTML);
                bot.execute(requestEmail);
                userSteps.put(chatId, "email");
                break;

            case "email":
                userLogins.get(chatId).setEmail(text);
                SendMessage requestPassword = new SendMessage(chatId, "Parolingizni kiriting:")
                        .parseMode(ParseMode.HTML);
                bot.execute(requestPassword);
                userSteps.put(chatId, "password");
                break;

            case "password":
                userLogins.get(chatId).setPassword(text);
                saveUserToDatabase(chatId);
                sendSubjectSelection(bot, chatId);
                break;

            case "subject":
                sendGrades(bot, chatId, text);
                break;

            default:
                SendMessage defaultMessage = new SendMessage(chatId, "Noma'lum holat. Iltimos, /start buyrug'ini qayta kiriting.")
                        .parseMode(ParseMode.HTML);
                bot.execute(defaultMessage);
                userSteps.remove(chatId);
                break;
        }
    }

    private static void saveUserToDatabase(long chatId) {
        User user = userLogins.get(chatId);
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (chat_id, first_name, last_name, email, password) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setLong(1, chatId);
            stmt.setString(2, user.getFirstName());
            stmt.setString(3, user.getLastName());
            stmt.setString(4, user.getEmail());
            stmt.setString(5, user.getPassword());
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void saveGradeToDatabase(long chatId, String subject, int grade) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO grades (user_id, subject, grade) VALUES (?, ?, ?)")) {
            stmt.setLong(1, chatId);
            stmt.setString(2, subject);
            stmt.setInt(3, grade);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void sendJoinChannelsMessage(TelegramBot bot, long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();

        for (String channel : REQUIRED_CHANNELS) {
            keyboard.addRow(new InlineKeyboardButton("Qo'shilish: " + channel).url("https://t.me/" + channel.substring(1)));
        }
        keyboard.addRow(new InlineKeyboardButton("Tekshirish").callbackData("check"));

        SendMessage request = new SendMessage(chatId, "Botdan to'liq foydalanish uchun quyidagi kanallarga qo'shiling:")
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard);
        bot.execute(request);
    }

    private static void sendWelcomeBackMessage(TelegramBot bot, long chatId) {
        SendMessage request = new SendMessage(chatId, "Xush kelibsiz! Fanlardan birini tanlang:")
                .parseMode(ParseMode.HTML);
        bot.execute(request);
        sendSubjectSelection(bot, chatId);
    }

    private static void sendSubjectSelection(TelegramBot bot, long chatId) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(
                new InlineKeyboardButton[]{
                        new InlineKeyboardButton("Onatili").callbackData("Onatili"),
                        new InlineKeyboardButton("Kimyo").callbackData("Kimyo"),
                        new InlineKeyboardButton("Matematika").callbackData("Matematika")
                }
        );

        SendMessage request = new SendMessage(chatId, "Iltimos, fanlardan birini tanlang:")
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard);
        bot.execute(request);
        userSteps.put(chatId, "subject");
    }

    private static void sendGrades(TelegramBot bot, long chatId, String subject) {
        Random rand = new Random();
        int grade = rand.nextInt(3) + 3; // Random grade between 3 and 5

        // Save the grade to the database
        saveGradeToDatabase(chatId, subject, grade);

        SendMessage request = new SendMessage(chatId, "Bugungi " + subject + " fanidan olgan bahoingiz: " + grade)
                .parseMode(ParseMode.HTML);
        bot.execute(request);
        userSteps.remove(chatId);
    }

    private static void sendAverageGrade(TelegramBot bot, long chatId) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT AVG(grade) FROM grades WHERE user_id = ? AND date >= now() - interval '7 days'")) {
            stmt.setLong(1, chatId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                double average = rs.getDouble(1);
                SendMessage request = new SendMessage(chatId, "Oxirgi 7 kun ichidagi o'rtacha bahoingiz: " + String.format("%.2f", average))
                        .parseMode(ParseMode.HTML);
                bot.execute(request);
            } else {
                SendMessage request = new SendMessage(chatId, "Sizning baholaringiz topilmadi.")
                        .parseMode(ParseMode.HTML);
                bot.execute(request);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void handleAdminCommands(TelegramBot bot, long chatId, String text) {
        String command = text.split(" ", 2)[0];
        String message = text.split(" ", 2).length > 1 ? text.split(" ", 2)[1] : "";

        if ("/admin_send".equals(command) && !message.isEmpty()) {
            sendAdminMessage(bot, message);
        }
    }

    private static void sendAdminMessage(TelegramBot bot, String message) {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT chat_id FROM users")) {
            while (rs.next()) {
                long chatId = rs.getLong(1);
                SendMessage request = new SendMessage(chatId, "Admin xabari: " + message)
                        .parseMode(ParseMode.HTML);
                bot.execute(request);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static class User {
        private String firstName;
        private String lastName;
        private String email;
        private String password;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}


