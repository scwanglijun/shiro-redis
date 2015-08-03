package wjw.shiro.redis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.HashedCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.crypto.RandomNumberGenerator;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.util.ByteSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * �û�: �����hash��,key��ģʽ��:keyPrefix+"users:$username" <br/>
 * ��ɫ: �����set��,key��ģʽ��:keyPrefix+"all_roles" <br/>
 * �û�ӵ�еĽ�ɫ:�����set��,key��ģʽ��:keyPrefix+"user_roles:$username" <br/>
 * ��ɫ��Ӧ��Ȩ��:�����hash��,key��ģʽ��:keyPrefix+"roles_permissions" <br/>
 * 
 * @author Administrator
 * 
 */
public class RedisRealm extends AuthorizingRealm {
  private static Logger logger = LoggerFactory.getLogger(RedisRealm.class);

  private static final int hashIterations = 100000; //number of iterations used in the hash.  not used when validating the password, so don't change it.

  private static final String F_PASSWORD = "password";

  private static final String F_SALT = "salt";

  private static final String F_NAME = "name";

  private static final String F_ALGORITHM = "algorithm";

  private static final String F_HASHITERATIONS = "hashIterations";

  private RedisManager redisManager;

  /**
   * The Redis key prefix for the Realm
   */
  private String keyPrefix = "shiro:realm:";

  //�û����keyǰ׺
  private String users_KeyPrefix = keyPrefix + "users:";

  //��ɫ���key
  private String all_roles_Key = keyPrefix + "all_roles";

  //�û�ӵ�еĽ�ɫ���keyǰ׺
  private String user_roles_KeyPrefix = keyPrefix + "user_roles:";
  //��ɫ����Щ�û�ӵ�п��keyǰ׺
  private String role_users_KeyPrefix = keyPrefix + "role_users:";

  //��ɫ��Ӧ��Ȩ�޿��key
  private String roles_permissions_Key = keyPrefix + "roles_permissions";

  /**
   * Returns the Redis session keys prefix.
   * 
   * @return The prefix
   */
  public String getKeyPrefix() {
    return keyPrefix;
  }

  /**
   * Sets the Redis sessions key prefix.
   * 
   * @param keyPrefix
   *          The prefix
   */
  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;

    //�û����keyǰ׺
    users_KeyPrefix = this.keyPrefix + "users:";

    //��ɫ���key
    all_roles_Key = this.keyPrefix + "all_roles";

    //�û�ӵ�еĽ�ɫ���keyǰ׺
    user_roles_KeyPrefix = this.keyPrefix + "user_roles:";
    //��ɫ����Щ�û�ӵ�п��keyǰ׺
    role_users_KeyPrefix = this.keyPrefix + "role_users:";

    //��ɫ��Ӧ��Ȩ�޿��key
    roles_permissions_Key = this.keyPrefix + "roles_permissions";
  }

  public RedisManager getRedisManager() {
    return redisManager;
  }

  public void setRedisManager(RedisManager redisManager) {
    this.redisManager = redisManager;

    // initialize the Redis manager instance
    this.redisManager.init();
  }

  protected boolean permissionsLookupEnabled = false;

  /**
   * Enables lookup of permissions during authorization. The default is "false"
   * - meaning that only roles are associated with a user. Set this to true in
   * order to lookup roles <b>and</b> permissions.
   * 
   * @param permissionsLookupEnabled
   *          true if permissions should be looked up during authorization, or
   *          false if only roles should be looked up.
   */
  public void setPermissionsLookupEnabled(boolean permissionsLookupEnabled) {
    this.permissionsLookupEnabled = permissionsLookupEnabled;
  }

  private HashedCredentialsMatcher matcher = new HashedCredentialsMatcher(Sha256Hash.ALGORITHM_NAME);
  private RandomNumberGenerator rng = new SecureRandomNumberGenerator();

  public RedisRealm() {
    matcher.setHashIterations(hashIterations);
    matcher.setStoredCredentialsHexEncoded(false);
    super.setCredentialsMatcher(matcher);
  }

  @Override
  public boolean supports(AuthenticationToken token) {
    return token instanceof UsernamePasswordToken;
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken authToken) throws AuthenticationException {
    if (!(authToken instanceof UsernamePasswordToken)) {
      throw new AuthenticationException("This realm only supports UsernamePasswordTokens");
    }
    UsernamePasswordToken token = (UsernamePasswordToken) authToken;

    if (token.getUsername() == null) {
      throw new AuthenticationException("Cannot log in null user");
    }

    return findPasswordForUsername(token.getUsername());
  }

  @SuppressWarnings("unchecked")
  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    //null usernames are invalid
    if (principals == null) {
      throw new AuthorizationException("PrincipalCollection method argument cannot be null.");
    }
    String username = (String) getAvailablePrincipal(principals);

    java.util.Set<String> roles = redisManager.smembers(user_roles_KeyPrefix + username);

    SimpleAuthorizationInfo info = new SimpleAuthorizationInfo(roles);

    if (permissionsLookupEnabled) {
      java.util.List<java.lang.String> permissionsList = redisManager.hmget(roles_permissions_Key, roles.toArray(new String[0]));
      Set<String> permissionsSet = new HashSet<String>(permissionsList.size());
      permissionsSet.addAll(permissionsList);
      info.setStringPermissions(permissionsSet);
    }

    return info;
  }

  /**
   * Does the actual mechanics of creating the Authentication info object from
   * the database.
   */
  public AuthenticationInfo findPasswordForUsername(String username) {
    Map<String, String> user = redisManager.hgetAll(users_KeyPrefix + username);

    if (user == null || user.size() == 0) {
      throw new UnknownAccountException("Unkown user " + username);
    }

    String password = user.get(F_PASSWORD);
    String salt = user.get(F_SALT);
    return new SimpleAuthenticationInfo(username, password, Sha256Hash.fromBase64String(salt), getName());
  }

  /**
   * ���, �û�: �����hash��,key��ģʽ��:keyPrefix+"users:$username" <br/>
   * 
   * @param username
   * @param plainTextPassword
   * @return
   */
  public boolean addUser(String username, String plainTextPassword) {
    ByteSource salt = rng.nextBytes();

    Map<String, String> user = new HashMap<String, String>(5);
    user.put(F_NAME, username);
    user.put(F_PASSWORD, new Sha256Hash(plainTextPassword, salt, hashIterations).toBase64());
    user.put(F_SALT, salt.toBase64());
    user.put(F_ALGORITHM, Sha256Hash.ALGORITHM_NAME);
    user.put(F_HASHITERATIONS, String.valueOf(hashIterations));

    if (redisManager.hmset(users_KeyPrefix + username, user).equalsIgnoreCase("OK")) {
      return true;
    } else {
      return false;
    }
  }

  /**
   * �޸�, �û�����!
   * 
   * @param username
   * @param plainTextPassword
   * @return
   */
  public boolean updateUserPassword(String username, String plainTextPassword) {
    return addUser(username, plainTextPassword);
  }

  /**
   * ɾ��, �û�,�Լ��û�ӵ�еĽ�ɫ!
   * 
   * @param username
   */
  public boolean removeUser(String username) {
    //1. ��ɾ��, ��ɫ������û�ӵ�еļ�¼
    java.util.Set<String> rolesSet = redisManager.smembers(user_roles_KeyPrefix + username);
    for (String role : rolesSet) {
      redisManager.srem(role_users_KeyPrefix + role, username);
    }

    //2. ɾ��, �û�
    redisManager.delStr(users_KeyPrefix + username);

    //3. ɾ��, �û�ӵ�еĽ�ɫ
    redisManager.delStr(user_roles_KeyPrefix + username);

    return true;
  }

  /**
   * ���, ��ɫ: �����set��,key��ģʽ��:keyPrefix+"all_roles" <br/>
   * 
   * @param roles
   * @return
   */
  public boolean addRole(String... roles) {
    redisManager.sadd(all_roles_Key, roles);
    return true;
  }

  /**
   * ɾ��, ��ɫ,�Լ���ɫӵ�е�Ȩ��!
   * 
   * @param role
   */
  public void removeRole(String role) {
    //1. ��ɾ��, ��ɫ����Щ�û�ӵ�еļ�¼
    java.util.Set<String> usersSet = redisManager.smembers(role_users_KeyPrefix + role);
    for (String user : usersSet) {
      redisManager.srem(user_roles_KeyPrefix + user, role);
    }
    //2. ɾ��, ��ɫ����Щ�û�ӵ�п��key 
    redisManager.delStr(role_users_KeyPrefix + role);

    //3. ɾ��, ��ɫ
    redisManager.srem(all_roles_Key, role);

    //4. ɾ��, ��ɫ��Ӧ��Ȩ��
    this.removeRolePermission(role);
  }

  /**
   * ���, �û�ӵ�еĽ�ɫ:�����set��,key��ģʽ��:keyPrefix+"user_roles:$username" <br/>
   * 
   * @param username
   * @param roles
   * @return
   */
  public boolean addUserOwnedRoles(String username, String... roles) {
    redisManager.sadd(user_roles_KeyPrefix + username, roles);
    for (String role : roles) {
      redisManager.sadd(role_users_KeyPrefix + role, username);
    }
    return true;
  }

  /**
   * �޸�, �û�ӵ�еĽ�ɫ!
   * 
   * @param username
   * @param roles
   */
  public void updateUserOwnedRoles(String username, String... roles) {
    this.removeUserOwnedRoles(username, roles);
    this.addUserOwnedRoles(username, roles);
  }

  /**
   * ɾ��, �û�ӵ�еĽ�ɫ!
   * 
   * @param username
   * @param roles
   */
  public void removeUserOwnedRoles(String username, String... roles) {
    redisManager.srem(user_roles_KeyPrefix + username, roles);
    for (String role : roles) {
      redisManager.srem(role_users_KeyPrefix + role, username);
    }
  }

  /**
   * ���, ��ɫ��Ӧ��Ȩ��:�����hash��,key��ģʽ��:keyPrefix+"roles_permissions" <br/>
   * 
   * @param rolesPermissions
   * @return
   */
  public boolean addRolePermission(String role, String permission) {
    redisManager.hset(roles_permissions_Key, role, permission);
    return true;
  }

  /**
   * �޸�, ��ɫ��Ӧ��Ȩ��
   * 
   * @param role
   * @param permission
   * @return
   */
  public boolean updateRolePermission(String role, String permission) {
    redisManager.hset(roles_permissions_Key, role, permission);
    return true;
  }

  /**
   * ɾ��, ��ɫ��Ӧ��Ȩ��!
   * 
   * @param role
   */
  public void removeRolePermission(String role) {
    redisManager.hdel(roles_permissions_Key, role);
  }
}
