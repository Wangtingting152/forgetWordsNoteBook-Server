package cn.johnnyzen.user;

import cn.johnnyzen.mail.MailUtil;
import cn.johnnyzen.util.code.CodeUtil;
import cn.johnnyzen.util.reuslt.ResultCode;
import cn.johnnyzen.util.reuslt.ResultUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.logging.Logger;

/**
 * @IDE: Created by IntelliJ IDEA.
 * @Author: 千千寰宇
 * @Date: 2018/10/6  23:42:02
 * @Description: ...
 */
@Service("userService")
public class UserService {
    private static final Logger logger = Logger.getLogger(UserService.class.getName());

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailUtil mailUtil;

    public int resetPassword(HttpServletRequest request, String token, String oldPswd, String newPswd){
        String logPrefix = "[UserService.resetPassword()] ";
        if(!oldPswd.equals(newPswd)){//新旧密码不一致时，才可更改
            User user = null;
            user = this.findOneByLoginUsersMap(request);

            //logger.info(logPrefix + "user:" + user.toString());
            if(user != null && user.getPassword().length() <= 1){//暂时找不到密码在何处被置空的临时办法...
                user = userRepository.findOneByUsername(user.getUsername());
            }

            if(oldPswd.equals(user.getPassword())){//原密码输入正确方可继续
                if(newPswd.length() <=18 && newPswd.length() > 5){//新密码长度[6,18]
                    user.setPassword(newPswd);

                    int result = this.saveToSession(request.getSession(), user, token);//更新Session登陆用户信息

                    if(result == 1){
                        this.save(user); // 保存到数据库中
                        logger.info(logPrefix + "用户" + user.toStringJustUsernameAndEmail() + "重置密码成功！");
                        return 1;//重置密码成功！
                    } else {
                        logger.info(logPrefix + "用户" + user.toStringJustUsernameAndEmail() + "重置密码失败(不存在loginUsersMap)！");
                        return -1;
                    }
                } else {
                    logger.info(logPrefix + "密码长度(6至18位数)不符合要求，重置失败。");
                    return -2;
                }
            } else {
                logger.info(logPrefix + "原密码输入错误，重置失败。");
                return -3;
            }
        } else {
            logger.info(logPrefix + "新密码与原密码一致，重置失败。");
            return -4;
        }
    }


    public int exitLogin( HttpServletRequest request, String loginToken){
        String logPrefix = "[UserService.exitLogin()] ";
        HttpSession session = null;
        session = request.getSession();
        session = request.getSession();
        Map<String, User> users = null;
        users = (Map<String, User>) session.getAttribute("loginUsersMap");
        if(users == null){
            logger.warning(logPrefix + "未曾登陆，退出失败。(loginUsersMap不存在)");
            return -1;
        }

        User user = null;
        user = users.remove(loginToken);//移除当前会话中登陆的该用户[注：可能还存在其他登陆用户]
        if(user == null){
            logger.warning(logPrefix + "未曾登陆，退出失败。(loginUsersMap不存在该用户)");
            return -2;
        }

        session.setAttribute("loginUsersMap", users);
        logger.warning(logPrefix + "用户(username:" + user.getUsername() + " email:"+user.getEmail() + ")退出成功！");
        return 1;
    }

    public Map<String, User> fetchLoginUsersMapFromSession(HttpSession session){
        Map<String, User> users = null;
        users = (Map<String, User>) session.getAttribute("loginUsersMap");
        return users;//null or map
    }

    /*
     * 通过session.loginUsersMap[username|email|token] + request.[username|email] 获取用户信息
     * 注：不再负责校验是否登陆过
     *
     * 返回null或者user
     **/
    public User findOneByLoginUsersMap(HttpServletRequest request){
        String logPrefix = "[UserService.findOneByLoginUsersMap()] ";
        Map<String, User> users = null;
        users = this.fetchLoginUsersMapFromSession(request.getSession());
        if(users != null){
//            for(User user:users.values()){
//                System.out.println("[UserService.findOneByLoginUsersMap] " + user);
//            }

            User user = null;

            String token = null;
            token = request.getParameter("token");
            logger.info(logPrefix + "token:" + token + " <from HttpServletRequest>");
            if(token == null){
                token = request.getHeader("token");
                logger.info(logPrefix + "token:" + token + " <from HttpServletRequest.Header>");
            }
            if(token != null){
                user = users.get(token); //可能为null，也可能为user真
                if(user != null){
                    //logger.info(logPrefix+"from token:" + user.toString());
                    return user;
                }
            }

            String username = null;
            username = request.getParameter("username");
            if(username != null){
                for(User item : users.values()){
                    //logger.info(logPrefix + "from username: " + item.toString());
                    if(item.getUsername().equals(username))
                        return item; //已登录成功过，无需再继续校验
                }
            }

            String email = null;
            email = request.getParameter("email");
            if(email != null){
                for(User item : users.values()){
                    //logger.info(logPrefix+"from email: " + item.toString());
                    if(item.getEmail().equals(email))
                       return item;  //已登录成功过，无需再继续校验
                }
            }
            return null; //username|email|token 均无user
        } else {
            logger.info(logPrefix+"loginUsersMap不存在！");
            return null;
        }
    }

    /*
     * 用户登录
     * login by: email | username + password
     * */
    public User login(HttpSession session,String username,String password,String email){
        String logPrefix = "[UserService.login()] ";
        User user = null;
        user = userRepository.findOneByUsernameAndPassword(username,password);
        if(user == null){
            user = userRepository.findOneByEmailAndPassword(email, password);
        }
        if(user != null){//登陆成功
            Map<String,User> map = null;
            map = (Map<String, User>) session.getAttribute("loginUsersMap");
            if(map == null){
                map = new HashMap<String,User>();
            }
            //1.1 已username(可能为null)+email(可能为null)和当前的登录时间生成token
            Calendar loginDatetime = Calendar.getInstance();
            //System.out.println("loginDatetime:" + loginDatetime.getTimeInMillis());
            user.setLastActiveDateTime(loginDatetime);
            String str = null;
            str = user.getUsername() + user.getEmail(); //user!=null:说明username或者emai其中一值不为null
            //System.out.println("user:" + user.toString());
            String token = CodeUtil.MD5(str + Long.toString(user.getLastActiveDateTime().getTimeInMillis()));
            user.setToken(token);

            logger.info(logPrefix+ "user:" + user.toString());
            map.put(token, user);
            session.setAttribute("loginUsersMap", map);
            logger.info(logPrefix + "用户(username:"+ user.getUsername() +" email:" + user.getEmail() +")登陆成功！");
            return user;
        }
        logger.info(logPrefix + "用户登陆失败！可能原因：用户名或密码错误，数据库通过username|email+password均查找不到该用户");
        return null;
    }

    public User flushLastActiveDateTime(User user){
        String logPrefix = "[UserService.flushLastActiveDateTime()] ";
        user.setLastActiveDateTime(Calendar.getInstance());//将活跃时间刷新为当前时刻
        logger.info(logPrefix + "已刷新用户(" + user.getUsername() + ")的活跃时间戳!");
        return user;
    }
    /*
     * login check 登陆校验
     *  1.通过token|username|email + request.session.loginUsersMap[token]
     *  2.登陆有效核验时间为40分钟
     *  3.刷新活跃时间
     **/
    public int loginCheck(HttpServletRequest request){
        String logPrefix = "[UserService.loginCheck()] ";
        HttpSession session = request.getSession();
        Map<String, User> users = null;
        users = (Map<String, User>) session.getAttribute("loginUsersMap");

        if(users != null){//登陆过，存在loginUsersMap
//            for(User user:users.values()){
//                logger.info(logPrefix + "<test> user:" + user.toString());
//            }

            User user = null;
            user = this.findOneByLoginUsersMap(request);

            if(user != null){
                String loginToken = null;
                loginToken = request.getParameter("token");//不可能不存在

                //计算相差的分钟数 + 刷新用户最近活跃时间
                Calendar lastActivateDateTime = user.getLastActiveDateTime();
                long diferenceMinutes = (Calendar.getInstance().getTimeInMillis() - lastActivateDateTime.getTimeInMillis()) / (60 * 1000);
                int validSeconds = 40;
                if(diferenceMinutes <= validSeconds){
                    //刷新用户的最近活跃时间戳
                    user = flushLastActiveDateTime(user);
                    users.put(loginToken, user); //重新写入 loginUsersMap
                    session.setAttribute("loginUsersMap", users);

//                    logger.info(logPrefix + "登陆成功且时间有效![" + user.toString() + "]");
                    logger.info(logPrefix + "登陆成功且时间有效![username:" + user.getUsername() + " email:" + user.getEmail() + "]");
                    return 1;
                } else {
                    logger.info(logPrefix + "登陆失败，原因：活跃时间(seconds:" + validSeconds + ")失效![" + user.toString() + "]");
                    return 0;//距离上次使用登陆后其它的服务时间[已大于40分钟]：登陆失效，并自动注销登陆
                }
            } else { //未曾登陆过：loginUsersMap中不存在对应匹配的username|email|token
                //判断是否为ajax请求
                String requestType = request.getHeader("X-Requested-With");
                if(requestType != null && "XMLHttpRequest".equals(requestType)){//ajax request
                    logger.info(logPrefix + "未曾登录![Ajax请求！]");
                    return -2;
                } else {//not ajax request
                    logger.info(logPrefix + "未曾登录!");
                    return -3;
                }
            }
        } else {
            logger.info(logPrefix + "loginUsersMap不存在，说明未曾登陆");
            return -1;
        }
    }

    public User findDistinctByActivateCode(String activateCode){
        User user = null;
        user = userRepository.findDistinctByActivateCode(activateCode);
        if(user != null){
            return user;//数据库中存在该带激活用户，返回成功。
        } else {
            return null;//数据库中未存在该带激活用户，返回失败。
        }
    }

    public int registerActivate(HttpSession session,String code){
        User user = null;
        user = this.findDistinctByActivateCode(code);
        if(user != null){
            session.setAttribute("register_user_email", user.getEmail());
            //设定用户状态为激活状态
            user.setAccountState(1);
            //为避免以后被锁用户使用此通过激活用户，需将 activateCode 值删除
            user.setActivateCode("");
            if(this.save(user)){ //更新用户信息成功
                logger.info("邮件激活账号["+user.getEmail()+"]成功！");
                return 1;
            } else {
                String message = "邮件激活账号["+user.getEmail()+"]成功，但更新用户信息故障！";
                logger.warning(message);
                return 0;
            }
        } else {
            logger.info("不存在该用户或可能已激活成功！");
            return -1;
        }
    }

    public int register(String username, String password, String email) {
        String logPrefix = "[UserService.register()] ";
        //利用正则表达式（可改进）验证邮箱是否符合邮箱的格式
        if(!email.matches("^\\w+@(\\w+\\.)+\\w+$")){
            logger.warning(logPrefix + "用户注册失败，邮箱格式不正确！");
            return -1;
        }

        //生成激活码
        String code = CodeUtil.getUUID();

        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setActivateCode(code);

        logger.info(user.toString());

        //如果用户未被注册，将用户保存到数据库
        if(userRepository.findOneByUsername(username) == null){
            if(userRepository.findOneByEmail(email) == null){
                userRepository.save(user); //保存到数据库
                mailUtil.setActivateCode(code); //设置激活码
                mailUtil.setReciverEmail(email);//设置收件人邮箱
                new Thread(mailUtil).start();//保存成功则通过线程的方式给用户发送一封邮件

                logger.warning(logPrefix + "用户(username:" + user.getUsername() + " email:"+user.getEmail() + ")注册中...激活邮件已发送");
                return 1;
            } else {//已被注册：：email
                logger.warning(logPrefix + "用户注册失败，该 email 已被注册");
                return -2;
            }
        } else {//已被注册username
            logger.warning(logPrefix + "用户注册失败，该 username 已被注册");
            return -3;
        }
    }

    /* 更新或者保存登陆用户信息到session.loginUsersMap 中 */
    public int saveToSession(HttpSession session , User user,String loginToken){
        String logPrefix = "[UserService.saveToSession()] ";
        Map<String, User> users = null;
        users = this.fetchLoginUsersMapFromSession(session);
        if(users != null){ //存在 loginUsersMap对象
            User oldUser = null;
            oldUser = users.remove(loginToken);

            //存不存在都没关系，仅仅是【更新】或者【保存】登陆用户信息到session.loginUsersMap
            users.put(loginToken, user);
            session.setAttribute("loginUsersMap", users);

            if(oldUser != null){//exists this logined user in session
                logger.info(logPrefix + "更新登陆用户信息成功，session中已存在该用户" + user.toStringJustUsernameAndEmail());
            } else {
                logger.info(logPrefix + "新增登陆用户信息成功，session中不存在该用户" + user.toStringJustUsernameAndEmail());
            }
            return 1;
        } else {
            logger.info(logPrefix + "session中不存在 loginUsersMap 对象，说明根本未登录，故无权限保存登录用户！");
            return -1; //session中不存在 loginUsersMap对象
        }
    }

    /* 创建新用户或者更新用户 */
    public boolean save(User user){
        User newUser = null;
        newUser = userRepository.save(user);
        return newUser == null?false:true;
    }
}