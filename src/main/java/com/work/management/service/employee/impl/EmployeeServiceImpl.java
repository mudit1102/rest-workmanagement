package com.work.management.service.employee.impl;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.work.management.configuration.ElasticConfigProperties;
import com.work.management.dto.BulkEmployeeDto;
import com.work.management.dto.EmployeeDocumentDto;
import com.work.management.dto.EmployeeDto;
import com.work.management.dto.FilterEmployeeDto;
import com.work.management.entity.Employee;
import com.work.management.entity.EntityType;
import com.work.management.entity.MetaEntity;
import com.work.management.entity.OperationType;
import com.work.management.repository.EmployeeRepository;
import com.work.management.service.employee.EmployeeService;
import com.work.management.utils.ExceptionUtils;
import com.work.management.web.rest.resource.AcceptedFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeServiceImpl implements EmployeeService {

  private static final String TOPIC = "employee";
  private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeServiceImpl.class);

  private final EmployeeRepository employeeRepository;
  private final KafkaTemplate<String, MetaEntity> kafkaTemplate;
  private final RestHighLevelClient restHighLevelClient;
  private final ElasticConfigProperties elasticConfigProperties;
  private final ObjectMapper objectMapper;
  private final SearchSourceBuilder searchSourceBuilder;
  private final Gson gson;

  @Autowired
  public EmployeeServiceImpl(EmployeeRepository employeeRepository,
      KafkaTemplate<String, MetaEntity> kafkaTemplate,
      RestHighLevelClient restHighLevelClient,
      ElasticConfigProperties elasticConfigProperties,
      ObjectMapper objectMapper,
      SearchSourceBuilder searchSourceBuilder, Gson gson) {
    this.employeeRepository = employeeRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.restHighLevelClient = restHighLevelClient;
    this.elasticConfigProperties = elasticConfigProperties;
    this.objectMapper = objectMapper;
    this.searchSourceBuilder = searchSourceBuilder;
    this.gson = gson;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRED)
  public EmployeeDto save(EmployeeDto employeeDto) {
    if (employeeRepository.findByPhoneNumber(employeeDto.getPhoneNumber()).isPresent()) {
      ExceptionUtils.throwEntityAlreadyExistsException(
          "Employee already exists, choose a different phone Number");
    }

    Employee employee = new Employee();
    BeanUtils.copyProperties(employeeDto, employee);

    employeeRepository.save(employee);

    MetaEntity metaEntity = MetaEntity.builder().entity(employee).entityType(EntityType.EMPLOYEE)
        .operationType(OperationType.CREATE).build();
    Message<MetaEntity> message = MessageBuilder.withPayload(metaEntity)
        .setHeader(KafkaHeaders.TOPIC, TOPIC).build();
    kafkaTemplate.send(message);

    BeanUtils.copyProperties(employee, employeeDto);
    return employeeDto;
  }

  @Override
  public EmployeeDto getEmployeeByUserName(String username) {
    Optional<Employee> employee = employeeRepository.findByUserName(username);
    if (!employee.isPresent()) {
      ExceptionUtils
          .throwEntityNotFoundException(
              String.format("Employee with UserName %s doesn't exists.", username));
    }

    EmployeeDto employeeDto = new EmployeeDto();
    BeanUtils.copyProperties(employee.get(), employeeDto);
    return employeeDto;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRED)
  public EmployeeDto updateEmployeeEntity(EmployeeDto employeeDto) {
    Optional<Employee> employee = employeeRepository.findById(employeeDto.getId());
    if (!employee.isPresent()) {
      ExceptionUtils
          .throwEntityNotFoundException(
              String
                  .format("Employee with Id %d doesn't exists.", employeeDto.getId()));
    }

    Employee oldEmployee = employee.get();
    if (!Objects.isNull(employeeDto.getUserName()) && !employeeDto.getUserName()
        .equals(oldEmployee.getUserName()) || !Objects.isNull(employeeDto.getId()) && !employeeDto
        .getId()
        .equals(oldEmployee.getId())) {
      ExceptionUtils.throwBadRequestException("Cannot update username or id");
    }
    Employee newEmployee = new Employee();
    BeanUtils.copyProperties(employeeDto, newEmployee);
    newEmployee.setUserName(oldEmployee.getUserName());
    newEmployee.setId(oldEmployee.getId());

    employeeRepository.save(newEmployee);

    EmployeeDto newEmployeeDto = new EmployeeDto();
    BeanUtils.copyProperties(newEmployee, newEmployeeDto);
    return newEmployeeDto;
  }

  @Override
  @Transactional(isolation = Isolation.SERIALIZABLE, propagation = Propagation.REQUIRED)
  public ImmutableList<Employee> bulkUpdate(BulkEmployeeDto bulkEmployeeDto) {
    if (!(bulkEmployeeDto.getEmployeeIds().size() == ImmutableSet
        .copyOf(bulkEmployeeDto.getEmployeeIds()).size())) {
      ExceptionUtils.throwBadRequestException("Duplicate employee ids exist");
    }

    final Map<AcceptedFields, String> acceptedFieldsMap = bulkEmployeeDto
        .getAcceptedFieldsMap();

    return bulkEmployeeDto.getEmployeeIds().stream()
        .map(employeeRepository::findById).map(employeeInDb -> {
          acceptedFieldsMap
              .forEach((field, value) -> field.processValue(employeeInDb.get(), value));
          return employeeInDb;
        }).map(Optional::get)
        .collect(toImmutableList());
  }

  @Override
  public String createDocument(final EmployeeDocumentDto employeeDocumentDto) {

    try {
      Map employeeDtoMapper = objectMapper.convertValue(employeeDocumentDto, Map.class);
      final IndexRequest indexRequest = new IndexRequest(
          elasticConfigProperties.getIndex().getName(), "_doc",
          String.valueOf(employeeDocumentDto.getId()))
          .source(employeeDtoMapper);

      return restHighLevelClient
          .index(indexRequest, RequestOptions.DEFAULT).getId();

    } catch (Exception ex) {
      LOGGER.error("The exception was thrown in createDocument ", ex);

    }
    return null;
  }

  @Override
  public List<EmployeeDocumentDto> filterEmployeeDocument(
      final FilterEmployeeDto filterEmployeeDto) {
    BoolQueryBuilder boolQueryBuilder = new BoolQueryBuilder();

    filterEmployeeDto.getFilterMap().forEach((operator, value) -> {
          switch (operator) {
            case EQUAL:
              value.forEach((fieldInfo, list) -> {
                boolQueryBuilder.must(QueryBuilders.termsQuery(fieldInfo.getFieldName(),fieldInfo.convert(list)));
              });
              break;
            case GREATER:
              value.forEach((fieldInfo, list) -> {
               boolQueryBuilder.filter(QueryBuilders.rangeQuery(fieldInfo.getFieldName()).gt(fieldInfo.convert(list).get(0)));
              });
              break;
            case LESS:
              value.forEach((fieldInfo, list) -> {
               boolQueryBuilder.filter(QueryBuilders.rangeQuery(fieldInfo.getFieldName()).lt(fieldInfo.convert(list).get(0)));
              });
          }
        }

    );

    searchSourceBuilder.query(boolQueryBuilder);
    SearchRequest searchRequest = new SearchRequest(elasticConfigProperties.getIndex().getName());
    searchRequest.source(searchSourceBuilder);
    try {
      SearchResponse searchResponse = restHighLevelClient
          .search(searchRequest, RequestOptions.DEFAULT);
      SearchHits hits = searchResponse.getHits();
      SearchHit[] searchHits = hits.getHits();
      List<EmployeeDocumentDto> employeeDocumentDtoList = new ArrayList<>();
      for (SearchHit hit : searchHits) {
        EmployeeDocumentDto employeeDocumentDto = gson
            .fromJson(hit.getSourceAsString(), EmployeeDocumentDto.class);
        employeeDocumentDtoList.add(employeeDocumentDto);
      }

      return ImmutableList.copyOf(employeeDocumentDtoList);
    } catch (Exception ex) {
      LOGGER.error("Exception thrown while getting the response", ex);
    }
    return null;
  }


}

