package net.swvn9.vote;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.OnlineStatus;

import java.io.File;

class Bot {

    //jda object
    static JDA jda;

    //the 'imgcache' directory.
    //basically a catch-all for any files the bot needs
    static final File imgcache = new File("cache"+File.separator);

    //text channels that the bot will use to run
    //the bot will not function if it can not see these text channels
    static final String submission = "341979061553463306";
    static final String approval = "341975342829010945";
    static final String voting = "341732684801769474";
    static final String queue = "341975553819279360";
    static final String rejected = "342013506041937921";
    static final String debug = "341980399628451840";
    static final String log = "344522457878888449";

    //emote/reactions that the bot uses for voting
    //again, the bot needs them to function
    static final String yes = "341980112704634880";
    static final String no = "341980112465559558";
    static final String veto = "342025850058702848";

    //Used in imgur API auth
    static String imgurRefresh;
    static String imgurClient;
    static String imgurSecret;

    //arg 0 should always be the bot's token
    public static void main(String[] args){

        //create the imgcache directory
        //eventually a config folder will be created here too
        if (!imgcache.exists())
            if (!imgcache.mkdir())
                System.out.println(imgcache.toString()+" could not be created. Does it already exist?");

        //get the imgur keys from arguments array
        imgurRefresh = args[1];
        imgurClient = args[2];
        imgurSecret = args[3];

        //initialise and connect with a new jda object. This object is literally the bot
        //only includes one listener "Listener" for all functions
        //doesn't need to be built async
        try {
            jda = new JDABuilder(AccountType.BOT)
                    .setToken(args[0])
                    .addEventListener(new Listener())
                    .setStatus(OnlineStatus.ONLINE)
                    .buildBlocking();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}

