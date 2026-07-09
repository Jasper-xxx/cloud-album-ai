package com.memory.xzp.service;

import com.memory.xzp.model.dto.UserAccountDTO;
import com.memory.xzp.model.vo.UserLoginVO;
import org.springframework.stereotype.Service;

@Service
public interface AuthService {


    /**
     * @Description: 账户注册
     * @Author: xzp
     * @Date: 2025/2/18,16:58:40
     */
    Boolean accountRegister(UserAccountDTO userAccountDTO);

    /**
    * @Description: 账户登录
    * @Author: xzp
    * @Date: 2025/2/18,16:58:40
    */
    UserLoginVO accountLogin(UserAccountDTO userAccountDTO);

    /**
     * @Description: 获取邮箱验证码
     * @Author: xzp
     * @Date: 2025/2/18,16:58:40
     */
    String getEmailCode(UserAccountDTO userAccountDTO);

    /**
     * @Description: 验证码登录
     * @Author: xzp
     * @Date: 2025/2/18,16:58:40
     */
    UserLoginVO codeLogin(UserAccountDTO userAccountDTO);

    void updatePassword(UserAccountDTO userAccountDTO);

    void updateEmail(UserAccountDTO userAccountDTO);

}
