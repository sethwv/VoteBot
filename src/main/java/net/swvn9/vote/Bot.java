package net.swvn9.vote;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.OnlineStatus;

import java.io.File;

public class Bot {

    public static JDA jda;
    public static final File imgcache = new File("cache"+File.separator);

    public static final String submission = "341979061553463306";
    public static final String approval = "341975342829010945";
    public static final String voting = "341732684801769474";
    public static final String queue = "341975553819279360";
    public static final String rejected = "342013506041937921";

    public static final String debug = "341980399628451840";

    public static final String yes = "341980112704634880";
    public static final String no = "341980112465559558";
    public static final String veto = "342025850058702848";

    public static void main(String[] args){
        try{
            if(!imgcache.exists()) imgcache.mkdir();
            jda = new JDABuilder(AccountType.BOT).setToken(args[0]).addEventListener(new Listener()).setStatus(OnlineStatus.ONLINE).buildAsync();
        } catch (Exception ignored) {}
    }
}

