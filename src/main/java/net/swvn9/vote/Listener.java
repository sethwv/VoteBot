package net.swvn9.vote;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.swvn9.vote.Bot.*;

class Listener extends ListenerAdapter {

    //threshold for emote submissions, VOTES not PERCENT
    private static final int aThresh = 3;
    //this format is used when displaying submission IDs
    private static final DecimalFormat df = new DecimalFormat("#.####");

    //set when the ready event is fired
    private static long start;
    //a count of the number of emotes SUBMITTED in the current session
    private static int sessionEmotes;
    //the master threshold for emote voting. PERCENT .10 == 10%
    private static double masterThresh = 0.10;

    //send an exception to the debug channel
    private static void sendException(Exception exx) {

        //create a new StringWriter
        StringWriter sw = new StringWriter();

        //capture the StackTrace with sw
        exx.printStackTrace(new PrintWriter(sw));

        //convert sw to a string (mostly not needed, maybe in the future)
        String exceptionAsString = sw.toString();

        //send the full captured stack trace to the debug channel
        jda.getTextChannelById(debug).sendMessageFormat("```java\n%s\n```", exceptionAsString).queue();

    }

    //used to set the master threshold at runtime
    private static void setMasterThresh(String a) {

        try {

            //the threshold is kept in a text file with one value,
            //writer is opened as a new file (append=false)
            FileWriter write = new FileWriter(new File(imgcache + File.separator + "masterThresh.txt"), false);

            //write the new value
            write.write(a);
            //close the writer, effectively saving the file
            write.close();

        } catch (Exception exx) {

            //send the exception to the debug channel
            sendException(exx);

        }

    }

    //get the master threshold from the file as a double
    private static double getMasterThresh() {

        try {

            //attempt to open the masterThresh file from the imgcache folder
            FileReader read = new FileReader(new File(imgcache + File.separator + "masterThresh.txt"));

            //open a scanner on the fileReader
            Scanner s = new Scanner(read);

            //get the next int from the file
            //I could really do with checking if the file is empty and giving a default threshold
            int i = s.nextInt();

            //the value is saved in the file as an integer,
            //convert it to a decimal and set the master variable
            Listener.masterThresh = (double) i / 100;

            //close the scanner and the reader
            s.close();
            read.close();

        } catch (Exception exx) {

            //send the exception to the debug channel
            sendException(exx);

        }

        //return the value of the masterThresh var
        //regardless of if it exists in the file
        return masterThresh;

    }

    private static void postImage(String imageUrl,String subId,User user){
        try {
            HttpResponse<JsonNode> response = Unirest.post("https://api.imgur.com/oauth2/token")
                    .header("content-type", "application/json")
                    .header("cache-control", "no-cache")
                    .body(new JSONObject()
                            .put("refresh_token",imgurRefresh)
                            .put("client_id",imgurClient)
                            .put("client_secret",imgurSecret)
                            .put("grant_type","refresh_token")
                    )
                    .asJson();

            HttpResponse<JsonNode> result = Unirest.post("https://api.imgur.com/3/image")
                    .header("Content-Type","application/json")
                    .header("authorization", "Bearer "+response.getBody().getObject().getString("access_token"))
                    .body(new JSONObject()
                            .put("image", imageUrl)
                            .put("description",user.getName()+"#"+user.getDiscriminator()+" ("+user.getId()+") @ "+ LocalDateTime.now().atZone(ZoneId.of("GMT")).format(DateTimeFormatter.ISO_DATE_TIME))
                            .put("title","SUB#"+subId)
                            .put("album", "TI5OI")
                            .toString()
                    )
                    .asJson();

        } catch (UnirestException e) {
            sendException(e);
        }
    }


    //the ready event, fires once the bot is up and running
    @Override
    public void onReady(ReadyEvent event) {

        //set start to the current nanoTime
        //used to calculate uptime later on
        Listener.start = System.nanoTime();

    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (event.getMessage().getContentRaw().equals("--help")) {
            for (Role r : event.getMember().getRoles()) {
                if (r.getId().equals("337624653797261313")) {
                    EmbedBuilder help = new EmbedBuilder();
                    help.addField("--t <int>", "View or Set the voting threshold percentage. (int is optional)", false);
                    help.addField("--status", "View the bot's Stats, Uptime, and Thresholds.", false);
                    help.addField("--help", "This command", false);
                    help.setColor(event.getGuild().getMember(jda.getSelfUser()).getColor());
                    help.setTimestamp(LocalDateTime.now(ZoneId.of("GMT")));
                    event.getChannel().sendMessage(help.build()).queue();
                    event.getMessage().delete().queue();
                    return;
                }
            }
        }
        if (event.getMessage().getContentRaw().startsWith("--t")) {
            for (Role r : event.getMember().getRoles()) {
                if (r.getId().equals("337624653797261313")) {
                    Scanner scan = new Scanner(event.getMessage().getContentRaw());
                    scan.next();
                    if (scan.hasNextInt()) {
                        setMasterThresh(scan.nextInt() + "");
                    }
                    event.getChannel().sendMessage(new EmbedBuilder().setTimestamp(LocalDateTime.now(ZoneId.of("GMT"))).setColor(event.getGuild().getMember(jda.getSelfUser()).getColor()).addField("Vote Threshold", "`" + (int) ((jda.getGuildById("335535819152687105").getMembers().size() * getMasterThresh()) + 1) + " Votes` \n`(" + (((jda.getGuildById("335535819152687105").getMembers().size() * getMasterThresh() + 1))) + ")` \n`(" + (int) (getMasterThresh() * 100) + "% of Guild)`", true).build()).queue();
                    event.getMessage().delete().queue();
                    return;
                }
            }
        }
        if (event.getMessage().getContentRaw().equals("--status")) {
            for (Role r : event.getMember().getRoles()) {
                if (r.getId().equals("337624653797261313")) {
                    EmbedBuilder stats = new EmbedBuilder();
                    long uptime = System.nanoTime() - start;
                    long days = TimeUnit.NANOSECONDS.toDays(uptime);
                    long hours = TimeUnit.NANOSECONDS.toHours(uptime - TimeUnit.DAYS.toNanos(days));
                    long minutes = TimeUnit.NANOSECONDS.toMinutes(uptime - TimeUnit.HOURS.toNanos(hours) - TimeUnit.DAYS.toNanos(days));
                    long seconds = TimeUnit.NANOSECONDS.toSeconds(uptime - TimeUnit.MINUTES.toNanos(minutes) - TimeUnit.HOURS.toNanos(hours) - TimeUnit.DAYS.toNanos(days));
                    StringBuilder uptimeString = new StringBuilder();
                    if (days != 0) uptimeString.append(days).append("d ");
                    if (hours != 0) uptimeString.append(hours).append("h ");
                    if (minutes != 0) uptimeString.append(minutes).append("m ");
                    if (seconds != 0) uptimeString.append(seconds).append("s");
                    stats.addField("Uptime", "" + uptimeString.toString() + "", false);
                    stats.setColor(event.getGuild().getMember(jda.getSelfUser()).getColor());
                    try {
                        int count;
                        FileReader read = new FileReader(new File(imgcache + File.separator + "count.txt"));
                        Scanner re = new Scanner(read);
                        count = re.nextInt();
                        stats.addField("Submission Stats", "`All-Time\t\t" + count + "`\n" + "`Session \t\t" + sessionEmotes + "`\n" + "`Waiting \t\t" + (event.getGuild().getTextChannelById(approval).getIterableHistory().complete().stream().filter(c -> c.getAuthor().isBot()).collect(Collectors.toList()).size()) + "`", true);
                    } catch (Exception exx) {
                        sendException(exx);
                    }
                    stats.addField("\u200B", "`Accepted\t\t" + event.getGuild().getTextChannelById(queue).getIterableHistory().complete().stream().filter(c -> c.getAuthor().isBot()).collect(Collectors.toList()).size() + "`\n" + "`Rejected\t\t" + event.getGuild().getTextChannelById(rejected).getIterableHistory().complete().stream().filter(c -> c.getAuthor().isBot()).collect(Collectors.toList()).size() + "`\n" + "`In Queue\t\t" + (event.getGuild().getTextChannelById(voting).getIterableHistory().complete().stream().filter(c -> c.getAuthor().isBot()).collect(Collectors.toList()).size()) + "`", true);
                    stats.addField("Vote Threshold", "`" + (int) ((jda.getGuildById("335535819152687105").getMembers().size() * getMasterThresh()) + 1) + " Votes (public voting)` \n`(" + ((df.format(jda.getGuildById("335535819152687105").getMembers().size() * getMasterThresh() + 1))) + ") (" + (int) (getMasterThresh() * 100) + "% of Guild)`\n` " + aThresh + " Votes (board voting)`", true);
                    stats.setTimestamp(LocalDateTime.now(ZoneId.of("GMT")));
                    event.getChannel().sendMessage(stats.build()).queue();
                    event.getMessage().delete().queue();
                    return;
                }
            }
        }
        if (event.getChannel().getId().equals(submission)) {
            try {
                int count;
                FileReader read = new FileReader(new File(imgcache + File.separator + "count.txt"));
                Scanner r = new Scanner(read);
                count = r.nextInt();
                r.close();
                read.close();
                count++;
                FileWriter write = new FileWriter(new File(imgcache + File.separator + "count.txt"), false);
                write.write(count + "");
                write.close();
                String formatted = String.format("%04d", count);
                File sub = new File(imgcache + File.separator + formatted + "_" + event.getAuthor().getId() + ".png");
                event.getMessage().getAttachments().get(0).download(sub);
                BufferedImage img = ImageIO.read(sub);
                if (!img.getColorModel().hasAlpha()) {
                    event.getAuthor().openPrivateChannel().queue(c -> {
                        c.sendMessage("\n**Your image, `SUB#" + formatted + "` does not meet our requirements to become an emote.**\n\nThe image you've tried to submit does not have a transparent background.\nIf you want to resubmit it, please follow the guidelines in <#341979061553463306>\n\n(Your Image)").addFile(sub).queue();
                        event.getMessage().delete().queue();
                        try {
                            TimeUnit.SECONDS.sleep(3);
                            Files.delete(sub.toPath());
                        } catch (Exception exx) {
                            sendException(exx);
                        }
                    });
                    return;
                }
                if (img.getWidth() > 512 || img.getHeight() > 512) {
                    event.getAuthor().openPrivateChannel().queue(c ->
                        c.sendMessage("\n**Your image, `SUB#" + formatted + "` does not meet our requirements to become an emote.**\n\nThe image you've tried to submit was **" + img.getWidth() + "**x**" + img.getHeight() + "** (pixels) in dimensions.\nWe require submitted images to be less than or equal to **512**x**512**.\nIf you want to resubmit it, please follow the guidelines in <#341979061553463306>\n\n(Your Image)").addFile(sub).queue()
                    );
                    event.getMessage().delete().queue();
                    try {
                        TimeUnit.SECONDS.sleep(3);
                        Files.delete(sub.toPath());
                    } catch (Exception exx) {
                        sendException(exx);
                    }
                    return;
                }
                jda.getTextChannelById(approval).sendMessageFormat("`\nSUB#%s`\nSubmitted by **%s**\n<@&336395133975003138> must approve or deny this submission.", formatted, event.getAuthor().getAsMention()).addFile(sub).queue(m -> {
                    m.addReaction(jda.getEmoteById(yes)).queue();
                    m.addReaction(jda.getEmoteById(no)).queue();
                    Listener.sessionEmotes++;
                });
                try {
                    TimeUnit.SECONDS.sleep(3);
                    Files.delete(sub.toPath());
                } catch (Exception exx) {
                    sendException(exx);
                }
                postImage(event.getMessage().getAttachments().get(0).getUrl(),formatted,event.getAuthor());
                event.getMessage().delete().queue();
            } catch (Exception exx) {
                sendException(exx);
            }
        }
    }

    @Override
    public void onGenericMessageReaction(GenericMessageReactionEvent event) {
        if (event.getUser().isBot()) return;
        String channelId = event.getChannel().getId();
        Emote yea = jda.getEmoteById(yes);
        Emote nay = jda.getEmoteById(no);
        Emote blk = jda.getEmoteById(veto);
        int yeacount = 0;
        int naycount = 0;

        int vThresh = (int) (jda.getGuildById("335535819152687105").getMembers().size() * getMasterThresh()) + 1;

        Message message;
        switch (channelId) {
            case approval:
                message = jda.getTextChannelById(approval).getMessageById(event.getMessageId()).complete();
                //noinspection Duplicates
                for (MessageReaction mr : message.getReactions()) {
                    if (mr.getEmote().getEmote().equals(nay)) naycount = mr.getCount();
                    if (mr.getEmote().getEmote().equals(yea)) yeacount = mr.getCount();
                }
                if (event.getReactionEmote().getEmote().equals(yea)) {
                    if (yeacount >= aThresh) {
                        File sub = new File(imgcache + File.separator + message.getId() + ".png");
                        message.getAttachments().get(0).download(sub);
                        jda.getTextChannelById(voting).sendMessage(message.getContentRaw().replace("<@&336395133975003138> must approve or deny this submission.", "Vote with <:Yea:341980112704634880> and <:Nay:341980112465559558>")).addFile(sub).queue(m -> {
                            m.addReaction(jda.getEmoteById(yes)).queue();
                            m.addReaction(jda.getEmoteById(no)).queue();
                            try {
                                Files.delete(sub.toPath());
                            } catch (Exception exx) {
                                sendException(exx);
                            }
                        });
                        message.delete().queue();
                    }
                } else if (event.getReactionEmote().getEmote().equals(nay)) {
                    if (naycount >= aThresh) {
                        File sub = new File(imgcache + File.separator + message.getId() + ".png");
                        message.getAttachments().get(0).download(sub);
                        jda.getTextChannelById(rejected).sendMessage(message.getContentRaw().replace("<@&336395133975003138> must approve or deny this submission.", "This emote was rejected in <#341975342829010945>.")).addFile(sub).queue(m -> {
                            try {
                                Files.delete(sub.toPath());
                            } catch (Exception exx) {
                                sendException(exx);
                            }
                        });
                        message.delete().queue();
                    }
                } else if (event.getReactionEmote().getEmote().equals(blk)) {
                    for (Role r : event.getMember().getRoles()) {
                        if (r.getId().equals("337624653797261313")) {
                            File sub = new File(imgcache + File.separator + message.getId() + ".png");
                            message.getAttachments().get(0).download(sub);
                            jda.getTextChannelById(rejected).sendMessage(message.getContentRaw().replace("<@&336395133975003138> must approve or deny this submission.", "This emote was vetoed in <#341975342829010945> by " + event.getMember().getAsMention())).addFile(sub).queue(m -> {
                                try {
                                    Files.delete(sub.toPath());
                                } catch (Exception exx) {
                                    sendException(exx);
                                }
                            });
                            message.delete().queue();
                        }
                    }
                }
                return;
            case voting:
                message = jda.getTextChannelById(voting).getMessageById(event.getMessageId()).complete();
                //noinspection Duplicates
                for (MessageReaction mr : message.getReactions()) {
                    if (mr.getEmote().getEmote().equals(yea)) yeacount = mr.getCount();
                    if (mr.getEmote().getEmote().equals(nay)) naycount = mr.getCount();
                }
                if (event.getReactionEmote().getEmote().equals(yea)) {
                    if (yeacount >= vThresh) {
                        File sub = new File(imgcache + File.separator + message.getId() + ".png");
                        message.getAttachments().get(0).download(sub);
                        jda.getTextChannelById(queue).sendMessage(message.getContentRaw().replace("Vote with <:Yea:341980112704634880> and <:Nay:341980112465559558>", "This emote has been voted in!")).addFile(sub).queue(m -> {
                            try {
                                Files.delete(sub.toPath());
                            } catch (Exception exx) {
                                sendException(exx);
                            }
                        });
                        message.delete().queue();
                    }
                } else if (event.getReactionEmote().getEmote().equals(nay)) {
                    if (naycount >= vThresh) {
                        File sub = new File(imgcache + File.separator + message.getId() + ".png");
                        message.getAttachments().get(0).download(sub);
                        jda.getTextChannelById(rejected).sendMessage(message.getContentRaw().replace("Vote with <:Yea:341980112704634880> and <:Nay:341980112465559558>", "This emote was rejected in <#341732684801769474>.")).addFile(sub).queue(m -> {
                            try {
                                Files.delete(sub.toPath());
                            } catch (Exception exx) {
                                sendException(exx);
                            }
                        });
                        message.delete().queue();
                    }
                } else if (event.getReactionEmote().getEmote().equals(blk)) {
                    for (Role r : event.getMember().getRoles()) {
                        if (r.getId().equals("337624653797261313")) {
                            File sub = new File(imgcache + File.separator + message.getId() + ".png");
                            message.getAttachments().get(0).download(sub);
                            jda.getTextChannelById(rejected).sendMessage(message.getContentRaw().replace("Vote with <:Yea:341980112704634880> and <:Nay:341980112465559558>", "This emote was vetoed in <#341732684801769474> by " + event.getMember().getAsMention())).addFile(sub).queue(m -> {
                                try {
                                    Files.delete(sub.toPath());
                                } catch (Exception exx) {
                                    sendException(exx);
                                }
                            });
                            message.delete().queue();
                        }
                    }
                }
        }
    }

}
