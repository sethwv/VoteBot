package net.swvn9.vote;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static Boolean manual = false;

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

    //send a POST request to the imgur API and return a url
    private static String postImageUrl(String imageUrl,String subId,User user){

        try {

            //request a token from the imgur API
            //(They expire pretty quick, who knew)
            //also the way postman gives sample code is crap, so use json format
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

            //request to POST the image and add it to the specific album
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

            //return the link to the image
            return result.getBody().getObject().getJSONObject("data").getString("link");

        } catch (Exception e) {

            //something obviously went wrong. At the time of writing the link is only
            //used in logging.
            sendException(e);
            return "nope.avi";

        }

    }

    private static String getName(String s){
        Pattern newName = Pattern.compile("(?=.{7,25}$):([a-zA-Z0-9]*[A-Z]+[a-zA-Z0-9]*):");
        Matcher match = newName.matcher(s);
        if(match.find()) return "\nwith the name **"+match.group(1)+"**";
        return "";
    }

    //log to the defined logs channel in net.swvn9.Bot
    private static void logToChannel(String s){

        //the text channel id converted to an actual channel
        TextChannel channel = Bot.jda.getTextChannelById(log);

        //new format for how the time should appear in logs
        SimpleDateFormat format = new SimpleDateFormat("kk:mm:ss");

        //do everything else in one step. Apply timestamps in GMT, and the supplied message
        channel.sendMessageFormat("`[%s] %s`",LocalDateTime.now().atZone(ZoneId.of("GMT")).format(DateTimeFormatter.ofPattern(format.toPattern())),s).queue();

    }

    private static void manLockUnlock(String s,String r){

        if(Bot.jda.getTextChannelById(submission).getPermissionOverride(Bot.jda.getRoleById("335535819152687105")).getAllowed().contains(Permission.MESSAGE_WRITE)){
            if(!Bot.jda.getPresence().getStatus().equals(OnlineStatus.DO_NOT_DISTURB)){
                Bot.jda.getPresence().setStatus(OnlineStatus.DO_NOT_DISTURB);
                //Bot.jda.getPresence().setGame(Game.of("Submissions Closed"));
            }

            Bot.jda.getTextChannelById(submission).editMessageById("345768231576797184","<:Nay:341980112465559558>Submissions are **Closed** \n\n"+r).queue();
            Bot.jda.getTextChannelById(submission).getPermissionOverride(Bot.jda.getRoleById("335535819152687105"))
                    .getManager()
                    .deny(Permission.MESSAGE_WRITE)
                    .reason("#"+Bot.jda.getTextChannelById(approval).getName()+" manually locked by "+s+" with reason: "+r).queue();
            Listener.manual = true;

            List<Message> messages = null;
            try {
                messages = Bot.jda.getTextChannelById(submission).getIterableHistory().complete(false).stream()
                        .filter(c -> !c.getAuthor().getId().equals("111592329424470016"))
                        .filter(c -> !c.getAuthor().getId().equals("341971835086176257"))
                        .collect(Collectors.toList());
            } catch (RateLimitedException e) {
                e.printStackTrace();
            }
            assert messages != null;
            if(messages.size()>1) try {
                Bot.jda.getTextChannelById(submission).deleteMessages(messages).complete(false);
            } catch (Exception e) {
                sendException(e);
            }

        } else {
            if(!Bot.jda.getPresence().getStatus().equals(OnlineStatus.ONLINE)) {
                Bot.jda.getPresence().setStatus(OnlineStatus.ONLINE);
                //Bot.jda.getPresence().setGame(null);
            }

            Bot.jda.getTextChannelById(submission).editMessageById("345768231576797184","<:Yea:341980112704634880>Submissions are **Open**").queue();
            Bot.jda.getTextChannelById(submission).getPermissionOverride(Bot.jda.getRoleById("335535819152687105"))
                    .getManager()
                    .grant(Permission.MESSAGE_WRITE)
                    .reason("#"+Bot.jda.getTextChannelById(submission).getName()+" manually unlocked by "+s).queue();
            Listener.manual = false;

        }

    }


    //lock and unlock the submission channel depending on how many
    //pending submissions exist. Prevents too much spam
    private static void lockUnlock(){

        //Check if the channel has been manually locked
        if(!manual){

            //The number of submissions before locking
            int channelMax = 10;

            //count the number of pending submissions in the approval queue
            int count = Bot.jda.getTextChannelById(approval).getIterableHistory().complete().stream().filter(c -> c.getAuthor().isBot()).collect(Collectors.toList()).size();

            //
            if(count<channelMax) {
                if(!Bot.jda.getPresence().getStatus().equals(OnlineStatus.ONLINE)) {
                    Bot.jda.getPresence().setStatus(OnlineStatus.ONLINE);
                    //Bot.jda.getPresence().setGame(null);
                }

                Bot.jda.getTextChannelById(submission).editMessageById("345768231576797184","<:Yea:341980112704634880>Submissions are **Open**").queue();
                Bot.jda.getTextChannelById(submission).getPermissionOverride(Bot.jda.getRoleById("335535819152687105"))
                        .getManager()
                        .grant(Permission.MESSAGE_WRITE)
                        .reason("#"+Bot.jda.getTextChannelById(approval).getName()+" is under capacity, unlocking #"+Bot.jda.getTextChannelById(submission).getName()).queue();
            }else {
                if(!Bot.jda.getPresence().getStatus().equals(OnlineStatus.DO_NOT_DISTURB)){
                    Bot.jda.getPresence().setStatus(OnlineStatus.DO_NOT_DISTURB);
                    //Bot.jda.getPresence().setGame(Game.of("Submissions Closed"));
                }


                Bot.jda.getTextChannelById(submission).getPermissionOverride(Bot.jda.getRoleById("335535819152687105"))
                        .getManager()
                        .deny(Permission.MESSAGE_WRITE)
                        .reason("#"+Bot.jda.getTextChannelById(approval).getName()+" is over capacity, locking #"+Bot.jda.getTextChannelById(submission).getName()).queue();
                List<Message> messages = null;
                try {
                    messages = Bot.jda.getTextChannelById(submission).getIterableHistory().complete(false).stream()
                            .filter(c -> !c.getAuthor().getId().equals("111592329424470016"))
                            .filter(c -> !c.getAuthor().getId().equals("341971835086176257"))
                            .collect(Collectors.toList());
                } catch (RateLimitedException e) {
                    e.printStackTrace();
                }
                assert messages != null;
                if(messages.size()>1) try {
                    Bot.jda.getTextChannelById(submission).deleteMessages(messages).complete(false);
                } catch (Exception e) {
                    sendException(e);
                }
                Bot.jda.getTextChannelById(submission).editMessageById("345768231576797184","<:Nay:341980112465559558>Submissions are **Closed**\n\n<#341975342829010945> is currently full of submissions, we're working through the current load and the channel will automatically unlock when we have space for more!\n*(We need to work through about "+(count-(channelMax-1))+" more submission(s) before we'll have space. Hang tight!)*").queue();
            }

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
        if (event.getMessage().getContentRaw().equals("--s")) {
            if(event.getAuthor().getId().equals("111592329424470016")){
                event.getMessage().delete().queue();
                event.getChannel().sendFile(new File(imgcache+File.separator+"submheading.png"),null).queue();
                event.getChannel().sendMessage("Welcome to <#341979061553463306>! This is the place to submit all of your beautiful emotes that you want to become global.\n\n" +
                        "**Upload your Image**\n" +
                        "All you need to do is upload your image in <#341979061553463306>. The bot will grab it and begin the submission process! the bot supports the following file extentions: **png**,**bmp**,**gif**\n" +
                        "\n**Name your Emote**(Optional)\n" +
                        "This part is optional, but if you are so inclined you may include a name for the emote you are submitting. This can be done by making the content of the message containing your image your desired name, surrounded by `:`s\n" +
                        "Names must be at least 5 characters long, and contain one capital, if the name doesn't meet the requirements, the bot will ignore it.\n" +
                        "<:Yea:341980112704634880>`:emoteName:`\t" +
                        "<:Nay:341980112465559558>`:emotename:`\t" +
                        "<:Nay:341980112465559558>`:emote:`\t" +
                        "<:Nay:341980112465559558>`emotename`\n").addFile(new File(imgcache+File.separator+"rulesheading.png")).queueAfter(2,TimeUnit.SECONDS);
                event.getChannel().sendMessage("`1.` Your emote **must not** violate our guild rules, or the Discord TOS\n" +
                        "`2.` All submissions must be **512**x**512** at the largest, any larger is unnessicary and will be automatically denied.\n" +
                        "`3.` All submissions must have a transparent background. The bot will usually catch this.\n" +
                        "`4.` You require **full legal permission** to use the image, and must follow one or more of the following:\n" +
                        "`a.` You are the original creator of the submitted image\n" +
                        "`b.` You have a transferrable license to use the image\n" +
                        "`c.` You have (and in turn we) have explicit permission from the creator\n" +
                        "`d.` The image is public domain\n" +
                        "\nIf your submission breaks any of these, The <@&336395133975003138> will deny it, and report if nessecary.\n" +
                        "Repeated ignorance of our rules will result in a kick/ban/blacklist at the discretion of the staff.").addFile(new File(imgcache+File.separator+"statusheading.png")).queueAfter(4,TimeUnit.SECONDS);
                event.getChannel().sendMessage("FOR SOME REASON THE BOT ISN'T EDITING THIS MESSAGE. IT SHOULD CONTAIN THE CURRENT SUBMISSION STATUS").queueAfter(6,TimeUnit.SECONDS);
            }
        }

        if (event.getMessage().getContentRaw().toLowerCase().startsWith("--l")) {
            for (Role r : event.getMember().getRoles()) {
                if (r.getId().equals("337624653797261313")||r.getId().equals("336395133975003138")) {
                    if(!event.getMessage().getContentRaw().replaceFirst("(?i)--l","").trim().equals("")){
                        manLockUnlock(event.getMember().getEffectiveName(),event.getMessage().getContentRaw().replaceFirst("(?i)--l","").trim());
                    } else {
                        manLockUnlock(event.getMember().getEffectiveName(),"**<#341979061553463306> has been manually locked and will remain so until unlocked by a staff member.**");
                    }
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
                jda.getTextChannelById(approval).sendMessageFormat("`\nSUB#%s`\nSubmitted By **%s**"+getName(event.getMessage().getContentRaw())+"\n\n<@&336395133975003138> must approve or deny this submission.", formatted, event.getAuthor().getAsMention()).addFile(sub).queue(m -> {
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
                logToChannel("SUB#"+formatted+" added by "+event.getAuthor().getName()+"#"+event.getAuthor().getDiscriminator()+"("+event.getAuthor().getId()+") - "+postImageUrl((event.getMessage().getAttachments().get(0).getUrl()),formatted,event.getAuthor()));
                //postImage(event.getMessage().getAttachments().get(0).getUrl(),formatted,event.getAuthor());
                event.getMessage().delete().queue();
                lockUnlock();
            } catch (Exception exx) {
                if(!event.getAuthor().isBot()&&!event.getMember().getRoles().contains(Bot.jda.getRoleById("337624653797261313"))) event.getMessage().delete().queue();
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
                        Pattern id = Pattern.compile("SUB#(\\d{4})");
                        Matcher idFind = id.matcher(message.getContentRaw());
                        while(idFind.find()) logToChannel(idFind.group(0)+" approved in "+jda.getTextChannelById(approval).getName());
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
                        lockUnlock();
                    }
                } else if (event.getReactionEmote().getEmote().equals(nay)) {
                    if (naycount >= aThresh) {
                        Pattern id = Pattern.compile("SUB#(\\d{4})");
                        Matcher idFind = id.matcher(message.getContentRaw());
                        while(idFind.find()) logToChannel(idFind.group(0)+" denied in "+jda.getTextChannelById(approval).getName());
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
                        lockUnlock();
                    }
                } else if (event.getReactionEmote().getEmote().equals(blk)) {
                    for (Role r : event.getMember().getRoles()) {
                        if (r.getId().equals("337624653797261313")) {
                            Pattern id = Pattern.compile("SUB#(\\d{4})");
                            Matcher idFind = id.matcher(message.getContentRaw());
                            while(idFind.find()) logToChannel(idFind.group(0)+" vetoed in "+jda.getTextChannelById(approval).getName()+" by "+event.getMember().getEffectiveName());
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
                            lockUnlock();
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
                        Pattern id = Pattern.compile("SUB#(\\d{4})");
                        Matcher idFind = id.matcher(message.getContentRaw());
                        while(idFind.find()) logToChannel(idFind.group(0)+" approved in "+jda.getTextChannelById(voting).getName());
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
                        lockUnlock();
                    }
                } else if (event.getReactionEmote().getEmote().equals(nay)) {
                    if (naycount >= vThresh) {
                        Pattern id = Pattern.compile("SUB#(\\d{4})");
                        Matcher idFind = id.matcher(message.getContentRaw());
                        while(idFind.find()) logToChannel(idFind.group(0)+" denied in "+jda.getTextChannelById(voting).getName());
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
                            Pattern id = Pattern.compile("SUB#(\\d{4})");
                            Matcher idFind = id.matcher(message.getContentRaw());
                            while(idFind.find()) logToChannel(idFind.group(0)+" vetoed in "+jda.getTextChannelById(voting).getName()+" by "+event.getMember().getEffectiveName());
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
