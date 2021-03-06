package io.cruii.bilibili.component;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import io.cruii.bilibili.constant.BilibiliAPI;
import io.cruii.bilibili.entity.BilibiliUser;
import io.cruii.bilibili.entity.TaskConfig;
import io.cruii.bilibili.exception.RequestException;
import io.cruii.bilibili.util.CosUtil;
import io.cruii.bilibili.util.HttpUtil;
import io.cruii.bilibili.util.ProxyUtil;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author cruii
 * Created on 2021/9/14
 */
@Log4j2
public class BilibiliDelegate {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36";

    @Getter
    private final TaskConfig config;

    private String proxyHost;

    private Integer proxyPort;

    public BilibiliDelegate(String dedeuserid, String sessdata, String biliJct) {
        TaskConfig taskConfig = new TaskConfig();
        taskConfig.setDedeuserid(dedeuserid);
        taskConfig.setSessdata(sessdata);
        taskConfig.setBiliJct(biliJct);
        taskConfig.setUserAgent(UA);
        this.config = taskConfig;
        setProxy();
    }

    public BilibiliDelegate(TaskConfig config) {
        this.config = config;
        if (CharSequenceUtil.isBlank(config.getUserAgent())) {
            config.setUserAgent(UA);
        }
        setProxy();
    }

    public void setProxy() {
        String proxy = ProxyUtil.get();

        setProxy(proxy);
    }

    private void setProxy(String proxy) {
        this.proxyHost = proxy.split(":")[0];
        this.proxyPort = Integer.parseInt(proxy.split(":")[1]);
    }

    /**
     * ????????????B????????????????????????
     *
     * @return B??????????????? {@link BilibiliUser}
     */
    public BilibiliUser getUser() {
        JSONObject resp = doGet(BilibiliAPI.GET_USER_INFO_NAV);

        // ??????????????????
        JSONObject data = resp.getJSONObject("data");
        // ??????????????????
        Boolean isLogin = data.getBool("isLogin");

        if (Boolean.FALSE.equals(isLogin)) {
            log.warn("??????Cookie?????????, {}, {}", config.getDedeuserid(), config.getSessdata());

            // ????????????????????????????????????????????????
            return getUser(config.getDedeuserid());
        }

        // ?????????????????????????????????
        // ????????????
        try {
            byte[] faces = getAvatarStream(data.getStr("face"));

            String path = "avatars" + File.separator + config.getDedeuserid() + ".png";
            File avatarFile = new File(path);
            if (avatarFile.exists()) {
                String localMd5 = SecureUtil.md5().digestHex(avatarFile);
                String remoteMd5 = SecureUtil.md5().digestHex(faces);
                if (!localMd5.equals(remoteMd5)) {
                    FileUtil.writeBytes(faces, avatarFile);
                }
            } else {
                FileUtil.writeBytes(faces, avatarFile);
            }

            // ????????? oss
            CosUtil.upload(avatarFile);
        } catch (Exception e) {
            log.error("??????????????????", e);
        }

        String uname = data.getStr("uname");
        // ???????????????
        String coins = data.getStr("money");

        // ?????????????????????
        JSONObject vip = data.getJSONObject("vip");

        // ??????????????????
        JSONObject levelInfo = data.getJSONObject("level_info");
        Integer currentLevel = levelInfo.getInt("current_level");

        // ???????????????
        JSONObject medalWallResp = getMedalWall();
        List<JSONObject> medals = medalWallResp.getJSONObject("data")
                .getJSONArray("list")
                .stream()
                .map(JSONUtil::parseObj)
                .map(medalObj -> {
                    JSONObject medal = JSONUtil.createObj();
                    medal.set("name", medalObj.getByPath("medal_info.medal_name", String.class));
                    medal.set("level", medalObj.getByPath("medal_info.level", Integer.class));
                    medal.set("colorStart", medalObj.getByPath("medal_info.medal_color_start", Integer.class));
                    medal.set("colorEnd", medalObj.getByPath("medal_info.medal_color_end", Integer.class));
                    medal.set("colorBorder", medalObj.getByPath("medal_info.medal_color_border", Integer.class));
                    return medal;
                })
                .sorted((o1, o2) -> o2.getInt("level") - o1.getInt("level"))
                .limit(2L)
                .collect(Collectors.toList());

        BilibiliUser info = new BilibiliUser();
        info.setDedeuserid(config.getDedeuserid());
        info.setUsername(uname);
        info.setCoins(coins);
        info.setLevel(currentLevel);
        info.setCurrentExp(levelInfo.getInt("current_exp"));
        info.setNextExp(currentLevel == 6 ? 0 : levelInfo.getInt("next_exp"));
        info.setMedals(JSONUtil.toJsonStr(medals));
        info.setVipType(vip.getInt("type"));
        info.setVipStatus(vip.getInt("status"));
        info.setIsLogin(true);

        return info;
    }

    /**
     * ???Cookie????????????????????????????????????
     *
     * @param userId B???uid
     * @return B??????????????? {@link BilibiliUser}
     */
    public BilibiliUser getUser(String userId) {
        Map<String, String> params = new HashMap<>();
        params.put("mid", userId);
        JSONObject resp = doGet(BilibiliAPI.GET_USER_SPACE_INFO, params);
        JSONObject baseInfo = resp.getJSONObject("data");
        if (resp.getInt("code") == -404 || baseInfo == null) {
            log.error("??????[{}]?????????", userId);
            return null;
        }
        byte[] faces = getAvatarStream(baseInfo.getStr("face"));

        String path = "avatars" + File.separator + config.getDedeuserid() + ".png";
        File avatarFile = new File(path);
        if (avatarFile.exists()) {
            String localMd5 = SecureUtil.md5().digestHex(avatarFile);
            String remoteMd5 = SecureUtil.md5().digestHex(faces);
            if (!localMd5.equals(remoteMd5)) {
                FileUtil.writeBytes(faces, avatarFile);
            }
        } else {
            FileUtil.writeBytes(faces, avatarFile);
        }

        // ????????? oss
        CosUtil.upload(avatarFile);

        BilibiliUser info = new BilibiliUser();
        info.setDedeuserid(userId);
        info.setUsername(baseInfo.getStr("name"));
        info.setLevel(baseInfo.getInt("level"));
        info.setIsLogin(false);

        return info;
    }

    /**
     * ?????????????????????
     *
     * @return ???????????????
     */
    public int getExp() {
        JSONObject resp = doGet(BilibiliAPI.GET_USER_SPACE_INFO);
        JSONObject baseInfo = resp.getJSONObject("data");
        if (resp.getInt("code") == -404 || baseInfo == null) {
            log.error("??????[{}]?????????", config.getDedeuserid());
            return 0;
        }
        return baseInfo.getInt("current_exp");
    }

    /**
     * ???????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getMedalWall() {
        Map<String, String> params = new HashMap<>();
        params.put("target_id", config.getDedeuserid());
        return doGet(BilibiliAPI.GET_MEDAL_WALL, params);
    }

    /**
     * ??????Cookie?????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject checkCookie() {
        return doGet(BilibiliAPI.GET_USER_INFO_NAV, null);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoinChangeLog() {
        return doGet(BilibiliAPI.GET_COIN_CHANGE_LOG);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getExpRewardStatus() {
        return doGet(BilibiliAPI.GET_EXP_REWARD_STATUS);
    }

    /**
     * ??????????????????UP????????????????????????BVID
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getFollowedUpPostVideo() {
        Map<String, String> params = new HashMap<>();
        params.put("uid", config.getDedeuserid());
        params.put("type_list", "8");
        params.put("from", "");
        params.put("platform", "web");

        return doGet(BilibiliAPI.GET_FOLLOWED_UP_POST_VIDEO, params);
    }

    /**
     * ????????????ID??????3???????????????
     *
     * @param regionId ??????ID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getTrendVideo(String regionId) {
        Map<String, String> params = new HashMap<>();
        params.put("rid", regionId);
        params.put("day", "3");

        return doGet(BilibiliAPI.GET_TREND_VIDEO, params);
    }

    /**
     * ????????????
     *
     * @param bvid       ?????????BVID
     * @param playedTime ??????????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject playVideo(String bvid, int playedTime) {
        Map<String, String> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("played_time", String.valueOf(playedTime));
        return doPost(BilibiliAPI.REPORT_HEARTBEAT, params);
    }

    /**
     * ????????????
     *
     * @param bvid ?????????BVID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject shareVideo(String bvid) {
        Map<String, String> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.SHARE_VIDEO, params);
    }


    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject mangaCheckIn(String platform) {
        Map<String, String> params = new HashMap<>();
        params.put("platform", platform);
        return doPost(BilibiliAPI.MANGA_SIGN, params);
    }

    /**
     * ??????????????????????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoinExpToday() {
        return doGet(BilibiliAPI.GET_COIN_EXP_TODAY);
    }

    /**
     * ????????????????????????
     *
     * @param bvid ??????BVID
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getVideoDetails(String bvid) {
        Map<String, String> params = new HashMap<>();
        params.put("bvid", bvid);
        return doGet(BilibiliAPI.GET_VIDEO_DETAILS, params);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getCoin() {
        return doGet(BilibiliAPI.GET_COIN);
    }


    /**
     * ???????????????????????????
     *
     * @param bvid ?????????bvid
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject checkDonateCoin(String bvid) {
        Map<String, String> params = new HashMap<>();
        params.put("bvid", bvid);
        return doGet(BilibiliAPI.CHECK_DONATE_COIN, params);
    }

    /**
     * ??????
     *
     * @param bvid    ?????????bvid
     * @param numCoin ?????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject donateCoin(String bvid, int numCoin, int isLike) {
        Map<String, String> params = new HashMap<>();
        params.put("bvid", bvid);
        params.put("multiply", String.valueOf(numCoin));
        params.put("select_like", String.valueOf(isLike));
        params.put("cross_domain", "true");
        params.put("csrf", config.getBiliJct());

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/video/" + bvid);
        headers.put("Origin", "https://www.bilibili.com");

        return doPost(BilibiliAPI.DONATE_COIN, params, headers);
    }

    /**
     * ?????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getLiveWallet() {
        return doGet(BilibiliAPI.BILI_LIVE_WALLET);
    }

    /**
     * ?????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject silver2Coin() {
        Map<String, String> params = new HashMap<>();
        params.put("csrf_token", config.getBiliJct());
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.SILVER_2_COIN, params);
    }

    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject liveCheckIn() {
        return doGet(BilibiliAPI.BILI_LIVE_CHECK_IN);
    }

    /**
     * ????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject listGifts() {
        return doGet(BilibiliAPI.LIST_GIFTS);
    }

    /**
     * ?????????????????????
     *
     * @param userId ??????id
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getLiveRoomInfo(String userId) {
        Map<String, String> params = new HashMap<>();
        params.put("mid", userId);

        return doGet(BilibiliAPI.GET_LIVE_ROOM_INFO, params);
    }

    /**
     * ?????????????????????
     *
     * @param userId  ?????????uid
     * @param roomId  ???????????????id
     * @param bagId   ??????id
     * @param giftId  ??????id
     * @param giftNum ????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject donateGift(String userId, String roomId,
                                 String bagId, String giftId, int giftNum) {
        Map<String, String> params = new HashMap<>();
        params.put("biz_id", roomId);
        params.put("ruid", userId);
        params.put("gift_id", giftId);
        params.put("bag_id", bagId);
        params.put("gift_num", String.valueOf(giftNum));
        params.put("uid", config.getDedeuserid());
        params.put("csrf", config.getBiliJct());
        params.put("send_ruid", "0");
        params.put("storm_beat_id", "0");
        params.put("price", "0");
        params.put("platform", "pc");
        params.put("biz_code", "live");

        return doPost(BilibiliAPI.SEND_GIFT, params);
    }

    /**
     * ??????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getChargeInfo() {
        Map<String, String> params = new HashMap<>();
        params.put("mid", config.getDedeuserid());

        return doGet(BilibiliAPI.GET_CHARGE_INFO, params);
    }

    /**
     * ??????
     *
     * @param couponBalance B????????????
     * @param upUserId      ???????????????userId
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject doCharge(int couponBalance, String upUserId) {
        Map<String, String> params = new HashMap<>();
        params.put("bp_num", String.valueOf(couponBalance));
        params.put("is_bp_remains_prior", "true");
        params.put("up_mid", upUserId);
        params.put("otype", "up");
        params.put("oid", config.getDedeuserid());
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.CHARGE, params);
    }

    /**
     * ??????????????????
     *
     * @param orderNo ???????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject doChargeComment(String orderNo) {
        Map<String, String> params = new HashMap<>();
        params.put("order_id", orderNo);
        params.put("message", "up???????????????");
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.COMMIT_CHARGE_COMMENT, params);
    }

    /**
     * ???????????????????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getMangaVipReward() {
        Map<String, String> params = new HashMap<>();
        params.put("reason_id", "1");

        return doPost(BilibiliAPI.GET_MANGA_VIP_REWARD, params);
    }

    /**
     * ?????????????????????
     *
     * @param type 1 = B??????  2 = ??????????????????
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject getVipReward(int type) {
        Map<String, String> params = new HashMap<>();
        params.put("type", String.valueOf(type));
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.GET_VIP_REWARD, params);
    }

    /**
     * ????????????
     *
     * @return ????????????JSON?????? {@link JSONObject}
     */
    public JSONObject readManga() {
        Map<String, String> params = new HashMap<>(4);
        params.put("device", "pc");
        params.put("platform", "web");
        params.put("comic_id", "26009");
        params.put("ep_id", "300318");

        return doPost(BilibiliAPI.READ_MANGA, params);
    }

    public JSONObject followUser(String uid) {
        Map<String, String> params = new HashMap<>();
        params.put("fid", uid);
        params.put("act", "1");
        params.put("re_src", "11");
        params.put("csrf", config.getBiliJct());

        return doPost(BilibiliAPI.RELATION_MODIFY, params);
    }

    /**
     * ??????B????????????????????????
     *
     * @param avatarUrl ????????????
     * @return ???????????????
     */
    private byte[] getAvatarStream(String avatarUrl) {
        URI uri;
        try {
            uri = new URIBuilder(avatarUrl).build();
        } catch (URISyntaxException e) {
            log.error("????????????????????????", e);
            return new byte[0];
        }
        HttpGet httpGet = new HttpGet(uri);

        try (CloseableHttpResponse response = HttpUtil.buildHttpClient().execute(httpGet)) {
            return EntityUtils.toByteArray(response.getEntity());
        } catch (Exception e) {
            log.error("???????????????????????????", e);
        }
        return new byte[0];
    }

    /**
     * ????????????
     *
     * @param username B????????????
     * @return ??????*????????????????????????
     */
    @Deprecated
    private String coverUsername(String username) {
        StringBuilder sb = new StringBuilder();

        if (username.length() > 2) {
            // ????????????????????????2???????????????????????????*?????????
            for (int i = 0; i < username.length(); i++) {
                if (i > 0 && i < username.length() - 1) {
                    sb.append("*");
                } else {
                    sb.append(username.charAt(i));
                }
            }
        } else {
            // ???????????????????????????2?????????????????????????????????
            sb.append(username.charAt(0)).append("*");
        }

        return sb.toString();
    }

    private JSONObject doGet(String url) {
        return doGet(url, MapUtil.empty());
    }

    /**
     * ????????????B???API??????
     *
     * @param url    ??????API??????
     * @param params ????????????????????? {@link MultiValueMap}
     * @return ????????????JSON?????? {@link JSONObject}
     */
    private JSONObject doGet(String url, Map<String, String> params) {

        URI uri = HttpUtil.buildUri(url, params);

        HttpGet httpGet = new HttpGet(uri);
        httpGet.setHeader("User-Agent", config.getUserAgent());
        httpGet.setHeader("Connection", "keep-alive");
        httpGet.setHeader("Cookie", "bili_jct=" + config.getBiliJct() +
                ";SESSDATA=" + config.getSessdata() +
                ";DedeUserID=" + config.getDedeuserid() + ";");


        return call(url, params, httpGet);
    }

    private JSONObject doPost(String url, Map<String, String> params) {
        return doPost(url, params, null);
    }

    private JSONObject doPost(String url, Map<String, String> params, Map<String, String> headers) {
        URI uri = HttpUtil.buildUri(url, params);
        HttpPost httpPost = new HttpPost(uri);
        List<NameValuePair> formData = new ArrayList<>();
        params.forEach((key, value) -> formData.add(new BasicNameValuePair(key, value)));
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formData, StandardCharsets.UTF_8);
        httpPost.setEntity(entity);

        httpPost.setHeader("Connection", "keep-alive");
        httpPost.setHeader("User-Agent", config.getUserAgent());
        httpPost.setHeader("Referer", "https://www.bilibili.com/");
        httpPost.setHeader("Cookie", "bili_jct=" + config.getBiliJct() +
                ";SESSDATA=" + config.getSessdata() +
                ";DedeUserID=" + config.getDedeuserid() + ";");
        if (headers != null) {
            headers.forEach(httpPost::setHeader);
        }
        return call(url, params, httpPost);
    }

    private JSONObject call(String url, Map<String, String> params, HttpUriRequest request) {
        try (CloseableHttpClient httpClient = HttpUtil.buildHttpClient(proxyHost, proxyPort);
             CloseableHttpResponse response = httpClient.execute(request)) {
            String responseBody = EntityUtils.toString(response.getEntity());
            log.debug("==============");
            log.debug("?????? API: {}", url);
            log.debug("????????????: {}", params);
            log.debug("????????????: {}", responseBody);
            log.debug("==============");
            EntityUtils.consume(response.getEntity());
            return JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            log.error("??????API[{}]??????", url, e);
            throw new RequestException(url, e);
        }
    }
}
