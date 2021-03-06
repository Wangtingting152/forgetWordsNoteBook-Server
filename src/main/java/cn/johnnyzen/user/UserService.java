package cn.johnnyzen.user;

import cn.johnnyzen.mail.MailUtil;
import cn.johnnyzen.util.code.CodeUtil;
import cn.johnnyzen.util.collection.CollectionUtil;
import cn.johnnyzen.util.file.FileUtil;
import cn.johnnyzen.util.reuslt.ResultCode;
import cn.johnnyzen.util.reuslt.ResultUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @IDE: Created by IntelliJ IDEA.
 * @Author: 千千寰宇
 * @Date: 2018/10/6  23:42:02
 * @Description: ...
 */
@Service("userService")
public class UserService {
    private static final Logger logger = Logger.getLogger(UserService.class.getName());

    //日志前缀字符串,方便通过日志定位程序
    private static String logPrefix = null;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MailUtil mailUtil;

    /**
     * 重置密码
     * @param request
     * @param token
     * @param newPswd
     * @param oldPswd
     */
    public int resetPassword(HttpServletRequest request, String token, String oldPswd, String newPswd){
        logPrefix = "[UserService.resetPassword()] ";
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


    /**
     * 退出登陆
     * @param request
     * @param loginToken
     */
    public int exitLogin( HttpServletRequest request, String loginToken){
        logPrefix = "[UserService.exitLogin()] ";
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

    /**
     * 通过session.loginUsersMap[username|email|token] + request.[username|email] 获取用户信息
     * 注：不再负责校验是否登陆过
     *
     * 返回null或者user
     * @param request
     **/
    public User findOneByLoginUsersMap(HttpServletRequest request){
        logPrefix = "[UserService.findOneByLoginUsersMap()] ";
        Map<String, User> users = null;
        users = this.fetchLoginUsersMapFromSession(request.getSession());
        if(users != null){
            for(User user:users.values()){
                System.out.println("[UserService.findOneByLoginUsersMap] " + user);
            }

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



    /**
     * 用户登录
     * login by: email | username + password
     * @param username
     * @param password
     * @param email
     **/
    public User login(HttpSession session,String username,String password,String email){
        logPrefix = "[UserService.login()] ";
        User user = null;
        user = userRepository.findOneByUsernameAndPassword(username,password);
        if(user == null){
            user = userRepository.findOneByEmailAndPassword(email, password);
        }
        if(user != null){//登陆成功
            if(user.getAccountState() != 1){ //判断用户状态是否被激活
                logger.info(logPrefix + "该账户" + user.toStringJustUsernameAndEmail() + "被锁定或者未激活");
//                return null;//此处注释，交由Controller处理账户激活状态问题
            }
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


    /**
     * 刷新登陆用户的最新活跃时间
     *  保障用户能够维持有效登陆
     *  @param user
     */
    public User flushLastActiveDateTime(User user){
        logPrefix = "[UserService.flushLastActiveDateTime()] ";
        user.setLastActiveDateTime(Calendar.getInstance());//将活跃时间刷新为当前时刻
        logger.info(logPrefix + "已刷新用户(" + user.getUsername() + ")的活跃时间戳!");
        return user;
    }

    /**
     * login check 登陆校验
     *  [注意：仅通过请求参数token判断用户是否登陆]
     *  [默认：登陆有效核验时间为40分钟]
     *  1.该会话是否已登录过用户[查询loginUsersMap是否存在]
     *      1.1 if loginUsersMap不存在(用户会话不存在任何登陆用户(全新会话))
     *          返回 1
     *      1.2 if loginUsersMap存在(该会话已经登陆过用户(但可能不止一名))
     *          1.2.1 通过request.[header/parameter].token获取用户请求的token
     *              如果token不存在，token参数不齐全
     *                  返回 2
     *              如果token存在，token参数齐全；
     *                  以token为key，在loginUsersMap中查找用户user
     *                      如果user不存在，说明该会话有登陆用户，但当前token无效
     *                          返回 3
     *                      如果user存在，说明会话有登陆用户，且token有效
     *                          查看该用户账户状态，则：
     *                              如果不为1，返回 4，该账户被锁定或者未激活
     *                          计算user登陆时间与当前时刻相差的分钟数
     *                              如果分钟数超过XX分钟，登陆超时已过期
     *                                  返回 5，登陆有效时间超时失效
     *                              如果分钟数未超过，登陆仍处于有效状态
     *                                  刷新user最近活跃时间，并重新存入loginUsersMap
     *                                  返回 6,用户登陆有效
     * @param request
     **/
    public int loginCheck(HttpServletRequest request){
        logPrefix = "[UserService.loginCheck()] ";
        HttpSession session = request.getSession();
        User user = null;
        Map<String, User> loginedUsers = null;
        loginedUsers = (Map<String, User>) session.getAttribute("loginUsersMap");

        if(loginedUsers == null){
            logger.info(logPrefix + "登陆失败，loginUsersMap不存在，说明未曾登陆");
            return 1;
        }

        //From [header/parameter] get:token
        String requestToken = null;
        requestToken = request.getParameter("token");
        if(requestToken == null){
            requestToken = request.getHeader("token");
        }
        if(requestToken == null){
            logger.info("登陆失败，请求参数不全[token]");
            return 2;
        }

        //以token为key，在loginUsersMap中查找用户user
        user = loginedUsers.get(requestToken);
        if(user == null){
            logger.info("登陆失败，该会话有登陆用户，但当前token(" + requestToken + ")无效。");
            return 3;
        }

        //查看该用户状态是否被激活
        if(user.getAccountState() != 1){
            logger.info("登陆失败，该会话有登陆用户，token(" + requestToken + ")有效，但账户未激活或者被锁定。");
            return 4;
        }

        //计算+校验用户活跃时间是否有效
        Calendar lastActivateDateTime = user.getLastActiveDateTime();
        long diferenceMinutes = (Calendar.getInstance().getTimeInMillis() - lastActivateDateTime.getTimeInMillis()) / (60 * 1000);
        int validSeconds = 40; //登陆的有效分钟数
        if(diferenceMinutes > validSeconds){//超时
            logger.info(logPrefix + "登陆失败，用户" + user.toStringJustUsernameAndEmail() + "登陆Token(" + requestToken + ")有效,但时间超时失效。");
            return 5;
        }

        //用户登陆完全有效：刷新用户的最近活跃时间戳+重新存入loginUsersMap
        user = flushLastActiveDateTime(user);
        loginedUsers.put(requestToken, user);
        session.setAttribute("loginUsersMap", loginedUsers);
//                    logger.info(logPrefix + "登陆成功且时间有效![" + user.toString() + "]");
        logger.info(logPrefix + "用户" + user.toStringJustUsernameAndEmail() + "<token:" + requestToken + ">登陆成功且时间有效!");
        return 6;
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


    /**
     * 注册激活
     * @param session
     * @param code
     */
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

    /**
     * 用户注册
     *  1.判断格式
     *      1.1 判断用户名格式是否正确
     *          如果错误，返回 1
     *      1.2 判断密码格式是否正确
     *          如果错误，返回 2
     *      1.3 判断邮箱格式是否正确
     *          如果错误，返回 3
     *  2.判断该用户名是否存在：
     *          如果已存在，返回 4
     *  3.判断邮箱是否已被注册：
     *          如果是，返回 5
     *  3.初始化用户信息，创建并存储用户
     *      返回 6，成功
     *  @param username
     *  @param password
     *  @param email
     */
    public int register(String username, String password, String email) {
        logPrefix = "[UserService.register()] ";
        //判断格式
        if(CollectionUtil.isLegalUsername(username.trim())!=4){
            return 1;
        }
        if(CollectionUtil.isLegalPassword(password)!=4){
            return 2;
        }
        if(CollectionUtil.isLegalEmail(email)!=4){
            return 3;
        }
        //判断用户名与邮箱的已存在性?
        if(userRepository.isExistsThisUsername(username) > 0){
            logger.info(logPrefix + "username<" + username + "> has existed,register fail!>");
            return 4;
        }
        if(userRepository.isExistsThisEmail(email) > 0){
            logger.info(logPrefix + "email<" + username + "> has existed,register fail!>");
            return 5;
        }

        //所有限制条件均通过，初始化用户信息，创建并存储用户信息
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setSex('U'); // U 未知
        user.setAccountState(0); // 0 未激活
        String code = CodeUtil.getUUID(); // 生成随机激活码
        user.setActivateCode(code);
        logger.info(logPrefix + "registering user information:" + user.toString());
        //存储到数据库中，并发送激活邮件到相应邮箱
        userRepository.save(user); //保存到数据库
        mailUtil.setReciverEmail(email);//设置收件人邮箱
        new Thread(mailUtil).start();//保存成功则通过线程的方式给用户发送一封邮件
        logger.warning(logPrefix + "用户(username:" + user.getUsername() + " email:"+user.getEmail() + ")注册中...激活邮件已发送");

        logger.info(logPrefix + user.toStringJustUsernameAndEmail() +" this new user register success,please receive activate email!");
        return 6;
    }

    /**
     * 更新或者保存登陆用户信息到session.loginUsersMap 中
     *  @param session
     *  @param user
     *  @param loginToken
     **/
    public int saveToSession(HttpSession session , User user,String loginToken){
        logPrefix = "[UserService.saveToSession()] ";
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

    /**
     * 更新用户头像
     * @param request
     * @param file
     * @param filePath
     */
    public int updateUserLogoUrl(HttpServletRequest request, MultipartFile file, String filePath){
        logPrefix = "[UserService.updateUserLogoUrl] ";
        User user=null;
        user=this.findOneByLoginUsersMap(request);
        String fileName=file.getOriginalFilename();
        String realFileName=null;
        String type=fileName.substring(fileName.lastIndexOf(".")+1,fileName.length());
        if(type!=null){
            if("GIF".equals(type.toUpperCase())||"PNG".equals(type.toUpperCase())||
                    "JPG".equals(type.toUpperCase())){
                realFileName=String.valueOf(System.currentTimeMillis())+fileName;
                try {
                    FileUtil.uploadFile(file.getBytes(),filePath,realFileName);
                    user.setLogoUrl(realFileName);
                    logger.info(logPrefix + " user's logo real path:" + filePath+realFileName);
                    userRepository.save(user);
                    return 1;
                } catch (Exception e) {
                    System.out.println("文件上传错误");
                    e.printStackTrace();
                }
                return 1;//图片上传成功
            }else {
                return -2;//文件类型不符合
            }
        }else {
            return -1;//文件类型为空
        }
    }

    /**
     * 更新个人信息 （仅用户名和性别）
     *      设置处理结果变量sexResult=0,usernameResult=0
     *      检查两个字段的传值情况
     *          如果两个参数都没有传：
     *              return usernameResult=0，更新失败
     *          如果两个参数都传：
     *              return usernameResult=1，更新失败，请选择其中一个字段上传
     *          如果传了username：
     *              判断username的格式，是否合法：
     *                  如果不合法：
     *                     return usernameResult=2，更新失败
     *              判断数据库中是否已经存在新username：
     *                  如果已经存在：
     *                     return usernameResult=3，更新失败
     *                  如果不存在：
     *                     设置user新的用户名。
     *                     保存user到数据库中。
     *                     return usernameResult=6
     *          如果传了sex：
     *              判断sex格式是否合法：
     *                  如果不合法：
     *                      return sexResult=4；
     *                  如果合法：
     *                      设置user新的性别值
     *                      保存user到数据库中
     *                      return sexResult=5
     *           return -1;//未知异常[原则上讲，程序逻辑不会运行到此处，也不会出现此情况]
     * @param request
     * @param username
     * @param sex [F or M]
     */
    public int updateUserInfo(HttpServletRequest request,String username,Character sex){
        logPrefix = "[UserService.updateUserInfo] ";
        User user = null;
        logger.info(logPrefix + " parameter: username<" + username + ">" + " sex<" + sex + ">");
        if(username == null && (sex == null || sex == ' ')){//都不传
            return 0;//fail
        }
        if(username == null){
            if(sex == null){
                return 0; //fail,都不传
            }
            //更新sex
            if(sex != ' '){//传了sex
                if(!CollectionUtil.isLegalSex(sex)){
                    return 4;//fail
                }
                //更新用户信息
                user = this.findOneByLoginUsersMap(request);//已经经过过滤器校验，user必然存在
                user.setSex(sex);
                userRepository.save(user);
                return 5;//success
            }
        } else {//username is not null
            if(sex != null){
                return 1; ////fail,都传
            }
            //更新username
            if(username != null){
                if(username.trim().length()>0){//传了username
                    if(CollectionUtil.isLegalUsername(username)!=4){//格式不合法
                        return 2;//fail
                    }
                    if(userRepository.isExistsThisUsername(username)>0){//已经存在该username
                        return 3;//fail
                    }
                    //更新用户信息
                    user = this.findOneByLoginUsersMap(request);//已经经过过滤器校验，user必然存在
                    user.setUsername(username);
                    userRepository.save(user);
                    return 6; //success
                }
            }
        }
        return -1; //未知异常[原则上讲，程序逻辑不会运行到此处，也不会出现此情况]
    }

    /**
     * 创建新用户或者更新用户
     *  @param user
     **/
    public boolean save(User user){
        User newUser = null;
        newUser = userRepository.save(user);
        return newUser == null?false:true;
    }
}
