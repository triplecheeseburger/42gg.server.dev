package io.pp.arcade.v1.domain.rank.service;

import io.pp.arcade.v1.domain.rank.RankRedisRepository;
import io.pp.arcade.v1.domain.rank.RedisKeyManager;
import io.pp.arcade.v1.domain.rank.dto.*;
import io.pp.arcade.v1.domain.rank.type.RankType;
import io.pp.arcade.v1.domain.season.Season;
import io.pp.arcade.v1.domain.season.SeasonService;
import io.pp.arcade.v1.domain.season.dto.SeasonDto;
import io.pp.arcade.v1.domain.season.dto.SeasonNameDto;
import io.pp.arcade.v1.domain.user.dto.UserDto;
import io.pp.arcade.v1.domain.rank.entity.RankRedis;

import io.pp.arcade.v1.global.type.GameType;
import io.pp.arcade.v1.global.type.RacketType;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import static io.pp.arcade.v1.global.type.GameType.SINGLE;


@Service
@AllArgsConstructor
public class RankRedisService {
    private final RedisTemplate<String, String> redisRank;
    private final RedisKeyManager redisKeyManager;
    private final RankRedisRepository rankRedisRepository;
    private final SeasonService seasonService;

    @Transactional
    public void updateRankPpp(RankUpdateDto updateDto) {
        Integer userId = updateDto.getUserDto().getId();
        Integer isWin = booleanToInt(updateDto.getIsWin());
        String curSeasonKey = redisKeyManager.getCurrentRankKey();

        RankRedis userRank = rankRedisRepository.findRank(curSeasonKey , userId);
        userRank.update(isWin, updateDto.getPpp());

        rankRedisRepository.updateRank(RedisRankUpdateDto.builder().seasonKey(curSeasonKey).userId(userId).userRank(userRank).build());

        String RankingKey = redisKeyManager.getCurrentRankingKey( updateDto.getGameType());
        rankRedisRepository.updateRanking(RedisRankingUpdateDto.builder().rankingKey(RankingKey).rank(userRank).ppp(updateDto.getPpp()).build());
    }

    /* 상태메시지 수정완료 */
    @Transactional
    public void updateRankStatusMessage(RankUpdateStatusMessageDto updateDto) {
        Integer userId = updateDto.getUserDto().getId();
        String curRankKey = redisKeyManager.getCurrentRankKey();

        RankRedis userRank = rankRedisRepository.findRank(curRankKey , userId);
        userRank.updateStatusMessage(updateDto.getStatusMessage());
        rankRedisRepository.updateRank(RedisRankUpdateDto.builder().seasonKey(curRankKey).userId(userId).userRank(userRank).build());
    }

    /* 어드민 - 유저 정보 수정*/
    @Transactional
    public void modifyRankPpp(RankRedisModifyPppDto modifyDto)
    {
        SeasonDto seasonDto = seasonService.findLatestRankSeason();

        /* Rank 수정 */
        Integer userId = modifyDto.getUserDto().getId();
        RankKeyGetDto keyGetDto = RankKeyGetDto.builder().seasonId(seasonDto.getId()).seasonName(seasonDto.getSeasonName()).build();
        String curRankKey = redisKeyManager.getRankKeyBySeason(keyGetDto);
        RankRedis rank = rankRedisRepository.findRank(curRankKey, userId);
        rank.modify(modifyDto.getModifyStatus(), modifyDto.getPpp());

        /* 수정된 Rank 적용 */
        GameType gameType = modifyDto.getGameType();
        rankRedisRepository.updateRank(RedisRankUpdateDto.builder().seasonKey(curRankKey).userId(userId).userRank(rank).build());

        /* 수정된 Ranking 적용 */
        String RankingKey = redisKeyManager.getCurrentRankingKey(gameType);
        rankRedisRepository.updateRanking(RedisRankingUpdateDto.builder().rankingKey(RankingKey).ppp(modifyDto.getPpp()).rank(rank).build());
    }

    public void addUserRank(UserDto userDto) {
        SeasonDto seasonDto = seasonService.findLatestRankSeason();

        RankRedis singleRank = RankRedis.from(userDto, SINGLE);
        singleRank.setPpp(seasonDto.getStartPpp());

        RankKeyGetDto rankKeyGetDto = RankKeyGetDto.builder().seasonId(seasonDto.getId()).seasonName(seasonDto.getSeasonName()).build();
        String rankKey = redisKeyManager.getRankKeyBySeason(rankKeyGetDto);
        RedisRankAddDto redisRankAddDto = RedisRankAddDto.builder().key(rankKey).userId(userDto.getId()).rank(singleRank).build();
        rankRedisRepository.addRank(redisRankAddDto);

        String rankingKey = redisKeyManager.getRankingKeyBySeason(rankKeyGetDto, SINGLE);
        RedisRankingAddDto redisRankingAddDto = RedisRankingAddDto.builder().rankingKey(rankingKey).rank(singleRank).build();
        rankRedisRepository.addRanking(redisRankingAddDto);
    }

    @Transactional
    public Boolean isEmpty() {
        return redisKeyManager.isEmpty();
    }

    @Transactional
    public void mysqlToRedis(List<RankDto> rankDtos) {
        HashMap<Integer,RankKeyGetDto> hashMap = new HashMap<>();

        /* 모든 시즌 정보를 해시맵에 저장 */
        List<SeasonNameDto> seasonList = seasonService.findAllSeason();
        seasonList.forEach(season -> {
            RankKeyGetDto keyGetDto = RankKeyGetDto.builder().seasonName(season.getName()).seasonId(season.getId()).build();
            hashMap.put(season.getId(), keyGetDto);
        });

        /* 모든 랭크 데이터를 레디스에 저장 */
        rankDtos.forEach(rankDto -> {
            RankKeyGetDto keyGetDto = hashMap.get(rankDto.getSeasonId());

            String rankKey = redisKeyManager.getRankKeyBySeason(keyGetDto);
            Integer userId = rankDto.getUser().getId();
            RankRedis rank = RankRedis.from(rankDto, rankDto.getGameType());
            RedisRankAddDto redisRankAddDto = RedisRankAddDto.builder().key(rankKey).userId(userId).rank(rank).build();
            rankRedisRepository.addRank(redisRankAddDto);

            String rankingKey = redisKeyManager.getRankingKeyBySeason(keyGetDto, rankDto.getGameType());
            RedisRankingAddDto redisRankingAddDto = RedisRankingAddDto.builder().rankingKey(rankingKey).rank(rank).build();
            rankRedisRepository.addRanking(redisRankingAddDto);
        });
    }

    public RankListDto findRankList(RankFindListDto findListDto) {
        Integer page = findListDto.getPageable().getPageNumber();
        Integer count = findListDto.getCount() == null ? findListDto.getPageable().getPageSize() : findListDto.getCount();
        GameType gameType = findListDto.getGameType();
        SeasonDto seasonDto = seasonService.findSeasonById(findListDto.getSeasonId());
        RankKeyGetDto rankKeyGetDto = RankKeyGetDto.builder().seasonId(seasonDto.getId()).seasonName(seasonDto.getSeasonName()).build();
        String rankingKey = redisKeyManager.getRankingKeyBySeason(rankKeyGetDto, gameType);

        Long size = redisRank.opsForZSet().size(rankingKey);

        int currentPage = (page > 1) ? page : 1;
        int totalPage = ((size.intValue() - 1) / count) + 1;
        int start = (currentPage - 1) * count;
        int end = start + count - 1;

        RedisRankingFindListDto redisRankingFindListDto = RedisRankingFindListDto.builder().rankingKey(rankingKey).start(start).end(end).rankKeyGetDto(rankKeyGetDto).build();
        List<RankUserDto> rankUserList = rankRedisRepository.findRankingList(redisRankingFindListDto);
        RankListDto rankListDto = RankListDto.builder()
                .currentPage(currentPage)
                .totalPage(totalPage)
                .rankList(rankUserList)
                .build();
        return rankListDto;
    }

    public List<RankUserDto> findCurrentRankList() {
        SeasonDto seasonDto = seasonService.findLatestRankSeason();
        RankKeyGetDto keyGetDto = RankKeyGetDto.builder().seasonId(seasonDto.getId()).seasonName(seasonDto.getSeasonName()).build();
        String rankKey = redisKeyManager.getRankKeyBySeason(keyGetDto);
        List<RankRedis> rankUserList = rankRedisRepository.findAllRank(rankKey);
        List<RankUserDto> rankUserDtos = rankUserList.stream().map(rank -> {
            RedisRankingFindDto findDto = RedisRankingFindDto.builder().rank(rank).keyGetDto(keyGetDto).build();
            Integer ranking = rankRedisRepository.findRanking(findDto);
            return RankUserDto.from(rank, ranking);
        }).collect(Collectors.toList());
        return rankUserDtos;
    }

    public RankUserDto findRankById(RankRedisFindDto findDto) {
        Integer userId = findDto.getUser().getId();
        SeasonDto season = findDto.getSeason() != null ? findDto.getSeason() : seasonService.findLatestRankSeason();
        RankKeyGetDto keyGetDto = RankKeyGetDto.builder().seasonId(season.getId()).seasonName(season.getSeasonName()).build();
        String curRankKey = redisKeyManager.getRankKeyBySeason(keyGetDto);

        RankRedis rank = rankRedisRepository.findRank(curRankKey, userId);
        if (rank == null) {
            rank = RankRedis.builder().id(userId).ppp(0).losses(0).winRate(0).wins(0).gameType(SINGLE).racketType(RacketType.NONE).statusMessage("").build();
        }
        Integer ranking = rankRedisRepository.findRanking(RedisRankingFindDto.builder().rank(rank).keyGetDto(keyGetDto).build());
        return (rank == null) ? null : RankUserDto.from(rank, ranking);
    }

    /* 시즌별 유저 랭킹 조회 */
    public Integer findRankingById(RankRankingFindDto findDto) {
        Integer ranking = RankType.UN_RANK;
        Integer userId = findDto.getUserId();
        SeasonDto seasonDto = findDto.getSeasonDto();
        RankKeyGetDto keyGetDto = RankKeyGetDto.builder().seasonName(seasonDto.getSeasonName()).seasonId(seasonDto.getId()).build();
        RankRedis rank = rankRedisRepository.findRank(redisKeyManager.getRankKeyBySeason(keyGetDto), userId);
        if (rank != null) {
            RedisRankingFindDto rankingFindDto = RedisRankingFindDto.builder().rank(rank).keyGetDto(keyGetDto).build();
            ranking = rankRedisRepository.findRanking(rankingFindDto);
        }
        return ranking;
    }
    private int booleanToInt(boolean value) {
        return value ? 1 : 0;
    }
}
